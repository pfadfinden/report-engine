// Progressive enhancement: narrows the group <select> to options whose label
// matches what's typed. The <select> stays the single source of truth for
// the submitted groupId, so this works purely by hiding/showing <option>s -
// no client-side routing, no separate id lookup, nothing to keep in sync.
(function () {
  var filterInput = document.getElementById('groupFilter');
  var groupSelect = document.getElementById('groupId');
  var status = document.getElementById('groupFilterStatus');

  if (!filterInput || !groupSelect || !status) {
    return;
  }

  var options = Array.prototype.slice.call(groupSelect.options);

  function applyFilter() {
    var query = filterInput.value.trim().toLowerCase();
    var visibleCount = 0;

    options.forEach(function (option) {
      var matches = query === '' || option.text.toLowerCase().indexOf(query) !== -1;
      option.hidden = !matches;
      if (matches) {
        visibleCount++;
      }
    });

    status.textContent = query === '' ? '' : visibleCount + ' von ' + options.length + ' Gruppen gefunden';
  }

  filterInput.addEventListener('input', applyFilter);

  // Enter in the filter field is meant to narrow the list, not submit the form.
  filterInput.addEventListener('keydown', function (event) {
    if (event.key === 'Enter') {
      event.preventDefault();
    }
  });
})();
