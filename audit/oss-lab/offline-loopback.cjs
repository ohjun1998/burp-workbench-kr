// Lab containment only: preserve application code and reject non-loopback network access.
const net=require('node:net');
const loopback=h=>h==='127.0.0.1'||h==='localhost'||h==='::1'||h==='[::1]';
const fetchOriginal=global.fetch;
global.fetch=(input,init)=>{const u=new URL(typeof input==='string'?input:input.url);if(!loopback(u.hostname))return Promise.reject(new Error('Offline lab: external fetch disabled'));return fetchOriginal(input,init);};
const connectOriginal=net.Socket.prototype.connect;
net.Socket.prototype.connect=function(...a){let n=Array.isArray(a[0])?a[0]:a;let o=typeof n[0]==='object'?n[0]:{port:n[0],host:typeof n[1]==='string'?n[1]:'localhost'};if(o.host&&!loopback(o.host)){queueMicrotask(()=>this.destroy(new Error('Offline lab: external socket disabled')));return this;}return connectOriginal.apply(this,a);};
const listenOriginal=net.Server.prototype.listen;
net.Server.prototype.listen=function(...a){if(typeof a[0]==='string'&&/^\d+$/.test(a[0]))a[0]=Number(a[0]);if(typeof a[0]==='number'){if(typeof a[1]==='function'||a[1]===undefined)a.splice(1,0,'127.0.0.1');else if(typeof a[1]==='string')a[1]='127.0.0.1';}else if(a[0]&&typeof a[0]==='object'){a[0]={...a[0],host:'127.0.0.1'};}return listenOriginal.apply(this,a);};
