import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
const script=new URL('./generate-assets.mjs',import.meta.url).pathname;
test('offline references are deterministic and stale output fails',()=>{
 const dir=mkdtempSync(join(tmpdir(),'assetlib-codegen-')); try {
  const input=join(dir,'catalog.json'), output=join(dir,'AppAssets.kt');
  const a={key:'travel.coast',symbol:['Travel','coast'],width:1200,height:900}, b={key:'tasks.garden',symbol:['Tasks','garden'],width:600,height:400};
  const run=flag=>spawnSync(process.execPath,[script,input,output,'com.example.app',...(flag?[flag]:[])],{encoding:'utf8'});
  writeFileSync(input,JSON.stringify({schemaVersion:1,placements:[a,b]})); assert.equal(run().status,0); const first=readFileSync(output,'utf8');
  writeFileSync(input,JSON.stringify({schemaVersion:1,placements:[b,a]})); assert.equal(run('--check').status,0);
  writeFileSync(output,first+'// stale'); assert.notEqual(run('--check').status,0);
  for(const bad of [[a,a],[{...a,symbol:['Travel','class']}],[{...a,width:0}],[{...a,key:'bad"injection'}],
   ...['AppAssets','AssetRef','AssetAccessibility'].flatMap(name=>[[{...a,symbol:[name,'coast']}],[{...a,symbol:['Travel',name]}]])]) {
   writeFileSync(input,JSON.stringify({schemaVersion:1,placements:bad})); assert.notEqual(run().status,0);
  }
 } finally { rmSync(dir,{recursive:true,force:true}); }
});
test('bundled accessibility validates locales and safely emits deterministic Kotlin strings',()=>{
 const dir=mkdtempSync(join(tmpdir(),'assetlib-accessibility-codegen-')); try {
  const input=join(dir,'catalog.json'), output=join(dir,'AppAssets.kt');
  const placement={key:'travel.coast',symbol:['Travel','coast'],width:1200,height:900};
  const run=value=>{
   writeFileSync(input,JSON.stringify({schemaVersion:1,placements:[{...placement,bundledAccessibility:value}]}));
   return spawnSync(process.execPath,[script,input,output,'com.example.app'],{encoding:'utf8'});
  };
  const description='A "$coast" ${dangerous()}\\path\nBeach';
  assert.equal(run({defaultLocale:'EN',descriptions:{th:'ชายฝั่ง',en:description}}).status,0);
  const first=readFileSync(output,'utf8');
  assert.ok(first.includes('bundledAccessibility = AssetAccessibility("EN", mapOf("en" to'));
  assert.ok(first.includes('\\$coast')); assert.ok(first.includes('\\${dangerous()}')); assert.ok(first.includes('\\nBeach'));
  assert.equal(run({descriptions:{en:description,th:'ชายฝั่ง'},defaultLocale:'EN'}).status,0);
  assert.equal(readFileSync(output,'utf8'),first);
  for(const value of [null,[],{defaultLocale:'en',descriptions:{}},{defaultLocale:'en',descriptions:{en:' ',EN:'Coast'}},
   {defaultLocale:'en',descriptions:{en:'\ufeff\u00a0'}},{defaultLocale:'en',descriptions:{en:'😀'.repeat(501)}},
   {defaultLocale:'en_US',descriptions:{en_US:'Coast'}},{defaultLocale:'de',descriptions:{en:'Coast'}},
   {defaultLocale:'en',descriptions:{en:42}},
   {defaultLocale:'en-x0',descriptions:Object.fromEntries(Array.from({length:33},(_,i)=>[`en-x${i}`,'Coast']))}]) assert.notEqual(run(value).status,0);
  assert.equal(run({defaultLocale:'en',descriptions:{en:'😀'.repeat(500)}}).status,0);
 } finally {rmSync(dir,{recursive:true,force:true});}
});
