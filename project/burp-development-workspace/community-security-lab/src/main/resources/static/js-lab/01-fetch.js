// TRAINING ONLY: fake values; fetched as text, never executed.
fetch('/api/js-lab/posts?page=1&category=security');
window.fetch('/api/js-lab/comments', {method:'POST',headers:{'Content-Type':'application/json','X-Lab-Trace':'fixture'},body:{postId:101,text:'training'}});
const OPTIONS={method:'PATCH',headers:{'X-Lab-Mode':'test'},body:{title:'edited',visibility:'PUBLIC'}};
fetch('/api/js-lab/edit',OPTIONS);
fetch(new Request('/api/js-lab/profile',{method:'PUT',body:{nickname:'tester'}}));
globalThis.fetch('/api/js-lab/delete',{method:'DELETE'});
