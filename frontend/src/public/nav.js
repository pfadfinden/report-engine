// Mobile slide-out menu. The toggle/panel are only active when this script runs
// (the `js` class), otherwise the nav stays inline and wraps.
(function () {
  var root = document.documentElement;
  root.classList.add('js');

  document.addEventListener('DOMContentLoaded', function () {
    var toggle = document.querySelector('.masthead_toggle');
    var panel = document.getElementById('masthead-panel');
    var backdrop = document.querySelector('.masthead_backdrop');
    var close = document.querySelector('.masthead_close');
    if (!toggle || !panel || !backdrop) return;

    toggle.hidden = false;
    var desktop = window.matchMedia('(min-width: 64rem)');

    function setOpen(open) {
      panel.classList.toggle('is-open', open);
      backdrop.hidden = !open;
      root.classList.toggle('menu-open', open);
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-label', open ? 'Menü schließen' : 'Menü öffnen');
      if (open) {
        close.focus();
      } else if (panel.contains(document.activeElement)) {
        toggle.focus();
      }
    }

    toggle.addEventListener('click', function () {
      setOpen(!panel.classList.contains('is-open'));
    });
    close.addEventListener('click', function () {
      setOpen(false);
    });
    backdrop.addEventListener('click', function () {
      setOpen(false);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && panel.classList.contains('is-open')) setOpen(false);
    });
    panel.addEventListener('click', function (e) {
      if (e.target.closest && e.target.closest('a')) setOpen(false);
    });
    var onChange = function (e) {
      if (e.matches) setOpen(false);
    };
    if (desktop.addEventListener) desktop.addEventListener('change', onChange);
  });
})();
