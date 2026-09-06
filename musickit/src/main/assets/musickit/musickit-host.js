// The page half of the bridge. Kotlin sends {type:'invoke', id, method, args} over the channel
// androidx.webkit's addWebMessageListener injects as window.musicKitBridge; every call answers with
// exactly one {type:'result', id, ok, value|error} message. MusicKit's own events arrive unsolicited
// as {type:'event', name, data}. No Apple credential is ever posted back: the Music User Token is
// read here, used here, and never leaves the page.
(function () {
  'use strict';

  var app = { name: 'Druware.MusicKit', build: '0.1.0' };
  var music = null;

  // The only egress point from page to native. The WebView2 original used
  // window.chrome.webview.postMessage; the injected object androidx.webkit gives us is named
  // musicKitBridge and is reachable only from this origin, because addWebMessageListener was scoped
  // to it. Everything else about the protocol is unchanged.
  function post(payload) {
    if (window.musicKitBridge) {
      window.musicKitBridge.postMessage(JSON.stringify(payload));
    }
  }

  function log(message) { post({ type: 'log', message: String(message) }); }
  function emit(name, data) { post({ type: 'event', name: name, data: data || {} }); }
  function result(id, ok, value, error) {
    post({ type: 'result', id: id, ok: ok, value: ok ? (value === undefined ? null : value) : null, error: error || null });
  }

  function describe(error) {
    if (!error) { return 'unknown error'; }
    if (typeof error === 'string') { return error; }
    var parts = [];
    if (error.name) { parts.push(error.name); }
    if (error.message) { parts.push(error.message); }
    if (error.errorCode) { parts.push('errorCode=' + error.errorCode); }
    if (parts.length === 0) {
      try { return JSON.stringify(error); } catch (e) { return String(error); }
    }
    return parts.join(': ');
  }

  function stateName(value) {
    try {
      var names = window.MusicKit && window.MusicKit.PlaybackStates;
      return (names && names[value] !== undefined) ? String(names[value]) : String(value);
    } catch (e) { return String(value); }
  }

  // MusicKit hands API failures back in more than one shape depending on where they were raised,
  // so both the status and the body are dug for rather than assumed.
  function statusOf(error) {
    if (!error) { return null; }
    if (typeof error.status === 'number') { return error.status; }
    if (typeof error.httpStatusCode === 'number') { return error.httpStatusCode; }
    if (error.response && typeof error.response.status === 'number') { return error.response.status; }
    // MusicKit raises its own API failures as { name: 'NOT_FOUND', message: '404' }, where the
    // status is only ever the message. Without this a 404 — which is an answer — reaches the host
    // as an unexplained failure instead of "no such thing".
    if (/^[1-5][0-9][0-9]$/.test(String(error.message))) { return Number(error.message); }
    return null;
  }

  function bodyOf(error) {
    if (!error) { return null; }
    if (error.data && error.data.errors) { return error.data; }
    if (error.body && error.body.errors) { return error.body; }
    if (error.errors) { return { errors: error.errors }; }
    return null;
  }

  function playParamsOf(item) {
    if (!item) { return null; }
    return item.playParams || (item.attributes && item.attributes.playParams) || null;
  }

  function summarise(item) {
    if (!item) { return null; }
    var a = item.attributes || item;
    var pp = playParamsOf(item) || {};
    var isSong = pp.kind === undefined || pp.kind === 'song';
    return {
      id: item.id || pp.id || null,
      title: a.name || a.title || item.title || null,
      artistName: a.artistName || item.artistName || null,
      catalogId: pp.catalogId || (isSong && !pp.isLibrary ? (pp.id || null) : null),
      libraryId: (isSong && pp.isLibrary) ? (pp.id || null) : null
    };
  }

  function requireInstance() {
    if (!music) { throw new Error('MusicKit is not configured yet.'); }
    return music;
  }

  var audioUnlocked = false;

  // Chromium will not let MusicKit's <audio> element stream in a document whose audio has never
  // been started, and a WebView nobody can see never gets the gesture that would start it. The
  // failure is silent and total: MusicKit accepts the queue, reports the now-playing item, goes
  // playing -> waiting -> loading, and stays in loading. play() does not settle for a minute and a
  // half, no mediaPlaybackError is ever raised, and every later call to that instance hangs behind
  // it. Rendering one near-silent oscillator through an AudioContext first is what avoids all of
  // that — proved by pairs of live runs that differ in nothing else.
  async function unlockAudio() {
    if (audioUnlocked) { return; }
    audioUnlocked = true;
    try {
      var ctx = new AudioContext();
      if (ctx.state === 'suspended') { await ctx.resume(); }

      var oscillator = ctx.createOscillator();
      var gain = ctx.createGain();
      gain.gain.value = 0.0001;
      oscillator.connect(gain);
      gain.connect(ctx.destination);
      oscillator.start();

      // The clock only advances while audio is really being rendered, so a second of it is the
      // proof as well as the unlocking. A hidden page's timers are throttled to about a tick a
      // second, which is why this waits on one timer rather than polling.
      await new Promise(function (resolve) { setTimeout(resolve, 1000); });
      log('audio unlocked: AudioContext ' + ctx.state + ', clock at ' + ctx.currentTime.toFixed(3) + ' s');

      try { oscillator.stop(); } catch (e) { /* already stopped */ }
      await ctx.close();
    } catch (error) {
      audioUnlocked = false;
      log('audio could not be unlocked, so playback may never leave loading: ' + describe(error));
    }
  }

  // Attached once per instance: a reconfigure hands back the same singleton, and listeners added
  // twice would double every event.
  function attach(instance) {
    if (!instance || instance.__druwareAttached) { return instance; }
    instance.__druwareAttached = true;

    instance.addEventListener('playbackStateDidChange', function (e) {
      emit('playbackStateDidChange', { state: stateName(e && e.state !== undefined ? e.state : instance.playbackState) });
    });
    instance.addEventListener('nowPlayingItemDidChange', function (e) {
      emit('nowPlayingItemDidChange', { item: summarise((e && e.item) || instance.nowPlayingItem) });
    });
    instance.addEventListener('authorizationStatusDidChange', function () {
      emit('authorizationStatusDidChange', { isAuthorized: !!instance.isAuthorized });
    });
    instance.addEventListener('queueItemsDidChange', function (items) {
      emit('queueItemsDidChange', { count: (items && items.length) ? items.length : 0 });
    });
    instance.addEventListener('mediaPlaybackError', function (e) {
      emit('mediaPlaybackError', { message: describe(e) });
    });

    return instance;
  }

  var announced = false;
  function announce() {
    if (announced) { return; }
    announced = true;
    emit('musickitloaded', { version: (window.MusicKit && window.MusicKit.version) || null });
  }

  if (window.MusicKit) { announce(); }
  document.addEventListener('musickitloaded', announce);

  window.onerror = function (message, source, line, column, error) {
    emit('error', { message: String(message) + ' @' + line + ':' + column, detail: error ? describe(error) : null });
  };
  window.addEventListener('unhandledrejection', function (e) {
    emit('error', { message: describe(e.reason) });
  });

  // One Apple Music API call. Never throws for an API failure: the status and the body go back so
  // the host can tell a 404 (which is an answer) from a 403 (which is not).
  async function apiCall(path, params) {
    var instance = requireInstance();
    try {
      var response = await instance.api.music(path, params || undefined);
      return {
        status: (response && response.response && response.response.status) || 200,
        body: (response && response.data) || null,
        error: null
      };
    } catch (e) {
      return { status: statusOf(e), body: bodyOf(e), error: describe(e) };
    }
  }

  var handlers = {
    status: function () {
      return {
        musicKitLoaded: !!window.MusicKit,
        version: (window.MusicKit && window.MusicKit.version) || null,
        configured: !!music,
        isAuthorized: !!(music && music.isAuthorized),
        storefrontId: music ? (music.storefrontId || null) : null
      };
    },

    configure: async function (args) {
      if (!window.MusicKit) { throw new Error("MusicKit JS has not loaded from Apple's CDN."); }
      if (args.appName) { app = { name: args.appName, build: args.appBuild || '0.1.0' }; }
      await window.MusicKit.configure({ developerToken: args.token, app: app });
      music = attach(window.MusicKit.getInstance());
      await unlockAudio();
      return {
        version: window.MusicKit.version || null,
        storefrontId: music.storefrontId || null,
        isAuthorized: !!music.isAuthorized
      };
    },

    // Rotation. MusicKit exposes developerToken as a getter only, so a second configure is the only
    // way to hand it a new one. It returns the same singleton, keeps the sign-in, and stops
    // playback — the host re-applies the queue afterwards.
    reconfigure: async function (args) {
      if (!window.MusicKit) { throw new Error("MusicKit JS has not loaded from Apple's CDN."); }
      await window.MusicKit.configure({ developerToken: args.token, app: app });
      music = attach(window.MusicKit.getInstance());
      return {
        version: window.MusicKit.version || null,
        storefrontId: music.storefrontId || null,
        isAuthorized: !!music.isAuthorized,
        developerTokenMatches: music.developerToken === args.token
      };
    },

    // authorize() resolves to the Music User Token. It is deliberately not returned: only the
    // boolean crosses the bridge, so no Apple credential ever reaches Kotlin or a log.
    authorize: async function () {
      var instance = requireInstance();
      await instance.authorize();
      return { isAuthorized: !!instance.isAuthorized };
    },

    unauthorize: async function () {
      var instance = requireInstance();
      await instance.unauthorize();
      return { isAuthorized: !!instance.isAuthorized };
    },

    apiGet: function (args) {
      return apiCall(args.path, args.params);
    },

    // Creating a library playlist needs a POST, and music.api.music offers none: its third argument
    // takes a fetchOptions object, but MusicKit 3.2526 ignores the method in it. The probe in the
    // integration test asks a catalog song for a POST and gets 200 with the song's data back — a
    // GET. So the request is made with a plain fetch here in the page, where the Music User Token
    // already is and so where it stays. Re-run that probe before trusting a newer MusicKit.
    apiPost: async function (args) {
      var instance = requireInstance();
      var response = await fetch('https://api.music.apple.com' + args.path, {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + instance.developerToken,
          'Music-User-Token': instance.musicUserToken,
          'Content-Type': 'application/json'
        },
        body: args.body
      });

      var text = await response.text();
      var parsed = null;
      if (text) { try { parsed = JSON.parse(text); } catch (e) { parsed = null; } }

      return {
        status: response.status,
        body: parsed,
        error: response.ok ? null : ('HTTP ' + response.status + ' ' + response.statusText),
        via: 'fetch'
      };
    },

    // 'items' names each identifier's type and carries its play parameters, which is what lets
    // MusicKit build the queue from the descriptor alone — no Apple request, and so nothing to
    // rate-limit or fail to resolve. An item without playParams sends MusicKit to
    // /v1/me/library/undefineds/…, so the parameters are not optional.
    setQueue: async function (args) {
      var instance = requireInstance();
      var started = Date.now();
      log('setQueue entry: ' + args.ids.length + ' id(s), state ' + stateName(instance.playbackState));
      var descriptor = {
        items: args.ids.map(function (id) {
          var isLibrary = String(id).indexOf('i.') === 0 || String(id).indexOf('l.') === 0;
          var playParams = { id: id, kind: 'song' };
          if (isLibrary) { playParams.isLibrary = true; }
          return {
            id: id,
            type: isLibrary ? 'library-songs' : 'songs',
            attributes: { playParams: playParams }
          };
        })
      };

      await instance.setQueue(descriptor);
      var queue = instance.queue;
      var items = queue && queue.items ? queue.items : [];
      log('setQueue exit: ' + items.length + ' item(s), state ' + stateName(instance.playbackState) +
        ', ' + (Date.now() - started) + ' ms');
      return { count: items.length };
    },

    play: async function () {
      var instance = requireInstance();
      var started = Date.now();
      log('play entry: state ' + stateName(instance.playbackState) +
        (instance.nowPlayingItem ? '' : ', nowPlayingItem is null'));
      await instance.play();
      log('play exit: state ' + stateName(instance.playbackState) + ', ' + (Date.now() - started) + ' ms');
      return { playbackState: stateName(instance.playbackState) };
    },

    pause: async function () {
      var instance = requireInstance();
      await instance.pause();
      return { playbackState: stateName(instance.playbackState) };
    },

    stop: async function () {
      var instance = requireInstance();
      await instance.stop();
      return { playbackState: stateName(instance.playbackState) };
    },

    skipToNext: async function () {
      var instance = requireInstance();
      await instance.skipToNextItem();
      return { playbackState: stateName(instance.playbackState), item: summarise(instance.nowPlayingItem) };
    },

    nowPlaying: function () {
      var instance = requireInstance();
      return { playbackState: stateName(instance.playbackState), item: summarise(instance.nowPlayingItem) };
    },

    // Reports whether this WebView build can start Widevine EME at all. Android WebView, unlike
    // Chrome, only gets a CDM when the host app grants PROTECTED_MEDIA_ID in onPermissionRequest,
    // and some OEM WebView forks carry no Widevine CDM whatsoever — so this is a capability probe,
    // not an assertion. Never throws: a rejection is an answer.
    probeWidevine: async function () {
      if (!navigator.requestMediaKeySystemAccess) {
        return { supported: false, error: 'requestMediaKeySystemAccess is not available.' };
      }

      try {
        var access = await navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
          initDataTypes: ['cenc'],
          audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
        }]);
        return { supported: true, keySystem: (access && access.keySystem) || null, error: null };
      } catch (error) {
        return { supported: false, keySystem: null, error: describe(error) };
      }
    }
  };

  window.musicKitHost = {
    invoke: function (id, method, args) {
      try {
        var handler = handlers[method];
        if (!handler) { result(id, false, null, "unknown bridge method '" + method + "'"); return; }
        Promise.resolve(handler(args || {})).then(
          function (value) { result(id, true, value, null); },
          function (error) { result(id, false, null, describe(error)); });
      } catch (error) {
        result(id, false, null, describe(error));
      }
    }
  };

  // The native half's only way in. WebView2 could hand the page a script to run; androidx.webkit's
  // channel is symmetric instead, so an invocation arrives here as a message rather than as an
  // evaluated expression — which is why nothing native ever needs to build JS source at run time.
  if (window.musicKitBridge) {
    window.musicKitBridge.onmessage = function (event) {
      var call = null;
      try { call = JSON.parse((event && event.data) || ''); } catch (e) { return; }
      if (!call || call.type !== 'invoke') { return; }
      window.musicKitHost.invoke(call.id, call.method, call.args);
    };
  }

  log('musickit-host.js ready; MusicKit ' + (window.MusicKit ? 'already present' : 'not yet loaded') +
    ', channel ' + (window.musicKitBridge ? 'attached' : 'MISSING'));
})();
