import { mkdirSync,copyFileSync } from 'node:fs';
mkdirSync('dist',{recursive:true});for(const f of ['index.html','app.js','styles.css'])copyFileSync('frontend/'+f,'dist/'+f);console.log('Production assets built in dist/. Set FRONTEND_DIR=dist and run npm start.');
