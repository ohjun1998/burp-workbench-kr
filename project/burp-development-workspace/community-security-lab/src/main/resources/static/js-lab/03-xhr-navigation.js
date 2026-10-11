// TRAINING ONLY: fake values; fetched as text, never executed.
const xhr=new XMLHttpRequest();
xhr.open('GET','/api/js-lab/xhr');
new WebSocket('wss://socket.community-lab.invalid/events');
new EventSource('/api/js-lab/events');
window.location.href='/api/js-lab/login';
location.assign('/api/js-lab/redirect');
window.location.replace('/api/js-lab/logout');
window.open('/api/js-lab/help','_blank');
