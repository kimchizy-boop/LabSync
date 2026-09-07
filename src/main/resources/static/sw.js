const C="gms-v4";
self.addEventListener("install",e=>e.waitUntil(self.skipWaiting()));
self.addEventListener("activate",e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener("fetch",e=>{
  const u=new URL(e.request.url);
  if(u.origin!==location.origin)return;
  if(e.request.method!=="GET")return;
  // Always fetch the HTML/JS/CSS and API from the live server so an old
  // service-worker cache can never trap the UI on an older broken build.
  if(e.request.destination==="script"||e.request.destination==="style"||e.request.destination==="document"||u.pathname.startsWith("/api/")){
    e.respondWith(fetch(e.request));
    return;
  }
  e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));
});
