// TRAINING ONLY: fake values; fetched as text, never executed.
const ROOT='/api/js-lab';
const ITEM='constant';
fetch(ROOT+'/'+ITEM);
fetch(`${ROOT}/template`);
fetch('/api/js-lab/escaped\x2fpath');
fetch('../relative');
fetch('./nearby');
const apiUrl='/api/js-lab/config';
const cfg={endpoint:'/api/js-lab/object',path:'/api/js-lab/path'};
