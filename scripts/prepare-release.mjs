import { readFileSync, copyFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const root=new URL('../',import.meta.url), source=new URL('sdk/build/outputs/aar/sdk-release.aar',root), folder=new URL('build/release/',root);
const name='assetlib-android-0.1.0-preview.1.aar';
const sha=createHash('sha256').update(readFileSync(source)).digest('hex');
mkdirSync(folder,{recursive:true}); copyFileSync(source,new URL(name,folder));
writeFileSync(new URL('SHA256SUMS',folder),`${sha}  ${name}\n`);
console.log(`${sha}  ${name}`);
