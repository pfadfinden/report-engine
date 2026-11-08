// Reload the form as soon as group or report changes, so the report list and
// parameters always match the selection. The "auswählen" buttons stay as a
// fallback for browsers without JavaScript.
(function () {
  var form = document.getElementById('requestReport');
  if (!form) return;

  // Auto-reload replaces the confirm buttons, which only exist for browsers without JavaScript.
  // (The "Link aktualisieren" button lives outside the form and is tied to it via the form attribute.)
  document.querySelectorAll('[data-no-js-fallback]').forEach(function (button) {
    button.hidden = true;
  });

  var group = document.getElementById('groupId');
  var report = document.getElementById('reportId');

  if (group) {
    group.addEventListener('change', function () {
      // The previously chosen report may not exist for the new group; let the server pick fresh.
      if (report) report.disabled = true;
      form.requestSubmit();
    });
  }
  if (report) {
    report.addEventListener('change', function () {
      form.requestSubmit();
    });
  }
  // Keep the shareable link in sync with the report settings without a page reload.
  var shareLink = document.querySelector('[data-share-url]');
  function updateShareLink() {
    var url = location.pathname + '?' + new URLSearchParams(new FormData(form)).toString();
    history.replaceState(null, '', url);
    if (shareLink) {
      shareLink.href = url;
      shareLink.textContent = url;
    }
  }
  // The debug info is rendered by the server, so it misses settings changed since page load.
  var debugPre = document.querySelector('[data-debug-lines]');
  var debugMail = document.querySelector('[data-debug-mailto]');
  function updateDebugInfo() {
    if (!debugPre) return;
    var lines = debugPre.textContent.split('\n').filter(function (line) {
      return line.indexOf('# Ausgewähltes Ausgabeformat:') !== 0 && line.indexOf('# Parameter ') !== 0;
    });
    var data = new FormData(form);
    lines.push('# Ausgewähltes Ausgabeformat: ' + (data.get('outputFormat') || '~Keines~'));
    data.forEach(function (value, key) {
      if (key.indexOf('p_') === 0) lines.push('# Parameter ' + key.substring(2) + ': ' + (value || '~Leer~'));
    });
    var text = lines.join('\n');
    debugPre.textContent = text;
    if (debugMail) {
      var base = debugMail.getAttribute('href').split('&body=')[0];
      debugMail.setAttribute('href', base + '&body=' + encodeURIComponent(text));
    }
  }

  function onSettingsChange() {
    updateShareLink();
    updateDebugInfo();
  }
  form.addEventListener('input', onSettingsChange);
  form.addEventListener('change', onSettingsChange);
  updateDebugInfo();

  // Disabled controls survive bfcache restores; make sure back navigation isn't left half-disabled.
  window.addEventListener('pageshow', function () {
    if (report) report.disabled = false;
  });
})();
