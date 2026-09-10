// Install before the engine loads so even syntax/import/startup failures reach the UI.
window.addEventListener('error',event=>{
 const detail=event.message || ('Datei konnte nicht geladen werden: '+(event.target?.src||'Lernmodul'));
 NativeDatabase.failed(detail);
},true);
window.addEventListener('unhandledrejection',event=>NativeDatabase.failed(String(event.reason?.message||event.reason)));
