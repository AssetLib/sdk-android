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
  for(const bad of [[a,a],[{...a,symbol:['Travel','class']}],[{...a,width:0}],[{...a,key:'bad"injection'}]]) {
   writeFileSync(input,JSON.stringify({schemaVersion:1,placements:bad})); assert.notEqual(run().status,0);
  }
 } finally { rmSync(dir,{recursive:true,force:true}); }
});
