// Deterministic brand artwork. The official logo pixels are preserved.
const {chromium}=require('@playwright/test');
const fs=require('node:fs/promises');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const dir=path.resolve(__dirname,'../resources/ui/assets/brand');
(async()=>{
 const browser=await chromium.launch();
 try{
  const page=await browser.newPage({viewport:{width:1200,height:630},deviceScaleFactor:1});
  await page.goto(pathToFileURL(path.join(__dirname,'brand/social-card.html')).href);
  await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:path.join(dir,'social-card-v2.png')});
  const logo=(await fs.readFile(path.join(dir,'referron-logo.png'))).toString('base64');
  // 340px square viewBox displays only the existing icon part of the official
  // wordmark sprite, at native resolution. No redrawing or recoloring.
  const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 340 340"><title>Referron</title><image width="1153" height="340" href="data:image/png;base64,${logo}"/></svg>`;
  await fs.writeFile(path.join(dir,'favicon-v2.svg'),svg);
  const sizes=[16,32,48,180,192,512],pngs=new Map();
  for(const size of sizes){
   const png=await page.evaluate(async({svg,size})=>{
    const img=new Image();img.src='data:image/svg+xml;base64,'+btoa(svg);await img.decode();
    const canvas=document.createElement('canvas');canvas.width=canvas.height=size;
    canvas.getContext('2d').drawImage(img,0,0,size,size);return canvas.toDataURL('image/png').split(',')[1];
   },{svg,size});
   const bytes=Buffer.from(png,'base64');pngs.set(size,bytes);
   await fs.writeFile(path.join(dir,`favicon-v2-${size}.png`),bytes);
  }
  const entries=[16,32,48];const header=Buffer.alloc(6+16*entries.length);header.writeUInt16LE(1,2);header.writeUInt16LE(entries.length,4);let offset=header.length;
  entries.forEach((size,i)=>{const pos=6+i*16,bytes=pngs.get(size);header[pos]=header[pos+1]=size;header.writeUInt16LE(1,pos+4);header.writeUInt16LE(32,pos+6);header.writeUInt32LE(bytes.length,pos+8);header.writeUInt32LE(offset,pos+12);offset+=bytes.length;});
  await fs.writeFile(path.join(dir,'favicon-v2.ico'),Buffer.concat([header,...entries.map(size=>pngs.get(size))]));
  console.log('Rendered 1200×630 social card, SVG favicon, six PNG sizes, and multi-resolution ICO.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
