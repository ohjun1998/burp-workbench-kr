// TRAINING ONLY: fake values; fetched as text, never executed.
const ROOT='/api/js-lab';
fetch(`${ROOT}/users/${userId}`);
function load(userId) { fetch(`${ROOT}/nested-users/${userId}`); }
function loadPost(postId) { fetch('/api/js-lab/post/'+postId); }
function shadow(ROOT) { fetch(ROOT+'/shadow'); }
fetch(ROOT+'/outside');
fetch('/api/js-lab/numeric/'+(1+2));
fetch('/api/js-lab/repeated');
fetch('/api/js-lab/repeated');
