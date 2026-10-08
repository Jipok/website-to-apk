const out = document.getElementById('out');
const li = (t) => { const e = document.createElement('li'); e.textContent = t; out.appendChild(e); };

li('origin: ' + location.origin);

try {
  localStorage.setItem('k', 'stored');
  li('localStorage: ' + localStorage.getItem('k'));
} catch (e) {
  li('localStorage: FAIL ' + e.name);
}

fetch('data.json')
  .then((r) => r.json())
  .then((d) => li('fetch: ' + JSON.stringify(d)))
  .catch((e) => li('fetch: FAIL ' + e));

import('./mod.js')
  .then((m) => li('module: ' + m.hello))
  .catch((e) => li('module: FAIL ' + e));
