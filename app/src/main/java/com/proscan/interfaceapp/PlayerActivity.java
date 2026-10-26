playerBtn.addEventListener('click', function() {
        if (navigator.vibrate) navigator.vibrate(25);
        if (window.__exportAllProscanStorage) window.__exportAllProscanStorage(false);
        if (window.AndroidApp && window.AndroidApp.openArchivePlayer) {
          window.AndroidApp.openArchivePlayer();
        }
      });