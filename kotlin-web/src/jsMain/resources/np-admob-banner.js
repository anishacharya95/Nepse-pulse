(function () {
  'use strict';

  let started = false;
  let interstitialReady = false;
  let navActions = 0;
  let lastInterstitialAt = 0;
  let interstitialShowing = false;
  const BANNER_ID = 'ca-app-pub-1565800134063497/8028868444';
  const INTERSTITIAL_ID = 'ca-app-pub-1565800134063497/1639379056';
  const APP_OPEN_ID = 'ca-app-pub-1565800134063497/9483086731';
  let appOpenReady = false;
  let appOpenShowing = false;
  const COOLDOWN_MS = 90 * 1000;
  const ACTIONS_BETWEEN_ADS = 3;

  function nativeAndroid() {
    return !!(window.Capacitor &&
      typeof window.Capacitor.isNativePlatform === 'function' &&
      window.Capacitor.isNativePlatform());
  }

  function report(message) {
    console.log('[NEPSE AdMob]', String(message));
  }

  function errorText(error) {
    if (error === null || error === undefined) return 'No error details supplied';
    if (typeof error === 'string') return error.trim() || 'Empty error string';
    if (error instanceof Error) {
      const name = error.name && error.name !== 'Error' ? error.name + ': ' : '';
      return name + (error.message || 'Error object has no message');
    }
    if (typeof error === 'object') {
      const fields = ['message', 'errorMessage', 'localizedDescription', 'code', 'domain', 'details'];
      const parts = fields
        .filter(key => error[key] !== undefined && error[key] !== null && String(error[key]).trim())
        .map(key => key + '=' + (typeof error[key] === 'object'
          ? JSON.stringify(error[key]) : String(error[key])));
      if (parts.length) return parts.join(' | ');
      try {
        const serialized = JSON.stringify(error);
        if (serialized && serialized !== '{}' && serialized !== '[]') return serialized;
      } catch (_) {}
      try {
        const ownKeys = Object.getOwnPropertyNames(error);
        if (ownKeys.length) return ownKeys.map(key => key + '=' + String(error[key])).join(' | ');
      } catch (_) {}
      return Object.prototype.toString.call(error);
    }
    return String(error);
  }

  async function loadAppOpen(admob) {
    try {
      await admob.loadAppOpen({ adId: APP_OPEN_ID });
      appOpenReady = true;
      report('App Open ad loaded');
    } catch (error) {
      appOpenReady = false;
      report('App Open load failed: ' + errorText(error));
    }
  }

  async function showAppOpenIfReady(admob) {
    if (!appOpenReady || appOpenShowing) return;
    appOpenShowing = true;
    appOpenReady = false;
    try {
      await admob.showAppOpen({ adId: APP_OPEN_ID });
      report('App Open ad displayed');
    } catch (error) {
      report('App Open display failed: ' + errorText(error));
    } finally {
      appOpenShowing = false;
      await loadAppOpen(admob);
    }
  }

  async function prepareInterstitial(admob) {
    try {
      report('Loading interstitial test ad…');
      await admob.prepareInterstitial({ adId: INTERSTITIAL_ID });
      interstitialReady = true;
      report('Interstitial prepared and ready');
    } catch (error) {
      interstitialReady = false;
      report('Interstitial load failed: ' + errorText(error));
    }
  }

  async function showInterstitialAtTransition(admob) {
    if (interstitialShowing || navActions < ACTIONS_BETWEEN_ADS) return;
    if (!interstitialReady) {
      report('3 navigation actions reached; waiting for interstitial to finish loading');
      return;
    }
    const remaining = COOLDOWN_MS - (Date.now() - lastInterstitialAt);
    if (remaining > 0) {
      report('3 navigation actions reached; cooldown active for ' +
        Math.ceil(remaining / 1000) + ' more seconds');
      return;
    }

    interstitialShowing = true;
    interstitialReady = false;
    try {
      report('Showing interstitial at navigation transition…');
      await admob.showInterstitial({ adId: INTERSTITIAL_ID });
      lastInterstitialAt = Date.now();
      navActions = 0;
      report('Interstitial show request completed; next ad after 3 more navigation actions');
    } catch (error) {
      report('Interstitial display failed: ' + errorText(error) +
        '; will retry on a later navigation action');
    } finally {
      interstitialShowing = false;
      await prepareInterstitial(admob);
    }
  }

  function installNavigationPlacement(admob) {
    document.addEventListener('click', function (event) {
      if (!nativeAndroid()) return;
      const target = event.target && event.target.closest
        ? event.target.closest('.nav button, .feature-item')
        : null;
      if (!target) return;
      navActions = Math.min(navActions + 1, ACTIONS_BETWEEN_ADS);
      report('Navigation progress ' + navActions + '/' + ACTIONS_BETWEEN_ADS +
        (interstitialReady ? '; interstitial ready' : '; interstitial loading'));
      if (navActions >= ACTIONS_BETWEEN_ADS) {
        Promise.resolve().then(() => showInterstitialAtTransition(admob));
      }
    }, true);
  }

  async function initializeWithRetry(admob) {
    const waits = [0, 1500, 3500];
    let lastError;
    for (let attempt = 0; attempt < waits.length; attempt += 1) {
      if (waits[attempt]) {
        report('Waiting for Android ad view (retry ' + (attempt + 1) + '/3)…');
        await new Promise(resolve => setTimeout(resolve, waits[attempt]));
      }
      try {
        await admob.initialize();
        return;
      } catch (error) {
        lastError = error;
        report('AdMob SDK init attempt ' + (attempt + 1) + ' failed: ' + errorText(error));
      }
    }
    throw lastError || new Error('AdMob SDK initialization failed');
  }

  async function startAdMob() {
    if (started || !nativeAndroid()) {
      if (!nativeAndroid()) report('Not running as a native Android app');
      return;
    }
    started = true;
    const admob = window.NP_CAP_ADMOB;
    if (!admob) {
      report('ERROR: native AdMob plugin unavailable');
      started = false;
      return;
    }

    try {
      report('Requesting consent information…');
      let consent;
      try {
        consent = await admob.requestConsentInfo();
      } catch (error) {
        throw new Error('Consent request failed: ' + errorText(error));
      }
      report('Consent status: ' + (consent && consent.status ? consent.status : 'unknown') +
        '; canRequestAds=' + (consent ? String(consent.canRequestAds) : 'no response'));

      if (consent && consent.isConsentFormAvailable &&
          String(consent.status).toLowerCase() === 'required') {
        report('Showing required consent form…');
        consent = await admob.showConsentForm();
        report('Consent form completed; canRequestAds=' +
          (consent ? String(consent.canRequestAds) : 'unknown'));
      }

      if (consent && consent.canRequestAds === false) {
        report('Consent does not currently allow ad requests');
        started = false;
        return;
      }

      report('Initializing Google Mobile Ads SDK…');
      await initializeWithRetry(admob);

      if (typeof admob.addListener === 'function') {
        await admob.addListener('bannerAdLoaded', () => report('TEST BANNER LOADED'));
        await admob.addListener('bannerAdFailedToLoad', error =>
          report('BANNER LOAD ERROR: ' + errorText(error)));
        await admob.addListener('bannerAdSizeChanged', size =>
          report('Banner size: ' + errorText(size)));
      }

      report('Requesting Google test banner…');
      await admob.showBanner({
        adId: BANNER_ID,
        adSize: 'ADAPTIVE_BANNER',
        position: 'BOTTOM_CENTER',
        margin: 0
      });
      report('Banner request sent; waiting for load callback…');
      installNavigationPlacement(admob);
      await prepareInterstitial(admob);
      await loadAppOpen(admob);
      if (window.NP_CAP_APP && typeof window.NP_CAP_APP.addListener === 'function') {
        await window.NP_CAP_APP.addListener('appStateChange', state => {
          if (state && state.isActive) showAppOpenIfReady(admob);
        });
      }
    } catch (error) {
      report('STARTUP ERROR: ' + errorText(error));
      started = false;
    }
  }

  function scheduleStart() {
    if (!nativeAndroid()) {
      report('Native Android bridge not ready; retrying…');
      setTimeout(scheduleStart, 1200);
      return;
    }
    startAdMob();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleStart, { once: true });
  } else {
    scheduleStart();
  }
})();
