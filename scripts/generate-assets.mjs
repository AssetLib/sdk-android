#!/usr/bin/env node
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
const [input, output, packageName = 'com.assetlib.demo', flag] = process.argv.slice(2);
if (!input || !output || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(packageName)) throw Error('Usage: node generate-assets.mjs catalog.json Output.kt com.example.app [--check]');
const raw=readFileSync(input,'utf8'); if(Buffer.byteLength(raw)>262144) throw Error('Catalog too large');
const data=JSON.parse(raw); if(data.schemaVersion!==1 || !Array.isArray(data.placements) || data.placements.length<1 || data.placements.length>100) throw Error('Invalid catalog');
const seen=new Set(), symbols=new Set(), groups=new Map();
const reserved=new Set('as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg value'.split(' '));
for(const name of ['AppAssets','AssetRef','AssetAccessibility']) reserved.add(name);
const validLocale=value=>typeof value==='string'&&value.length<=63&&/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/.test(value);
const kotlinString=value=>JSON.stringify(value).replace(/\$/g,'\\$');
function accessibility(value) {
 if(!value||typeof value!=='object'||Array.isArray(value)||!validLocale(value.defaultLocale)||!value.descriptions||typeof value.descriptions!=='object'||Array.isArray(value.descriptions)) throw Error('Invalid accessibility metadata');
 const entries=Object.entries(value.descriptions), locales=new Set();
 if(entries.length<1||entries.length>32) throw Error('Invalid accessibility locale count');
 for(const [locale,description] of entries) {
  if(!validLocale(locale)||locales.has(locale.toLowerCase())||typeof description!=='string'||!description.trim()||description.length>1000) throw Error('Invalid accessibility description');
  locales.add(locale.toLowerCase());
 }
 if(!locales.has(value.defaultLocale.toLowerCase())) throw Error('Missing default accessibility description');
 return `, bundledAccessibility = AssetAccessibility(${kotlinString(value.defaultLocale)}, mapOf(${entries.sort(([a],[b])=>a<b?-1:a>b?1:0).map(([locale,description])=>`${kotlinString(locale)} to ${kotlinString(description)}`).join(', ')}))`;
}
for(const p of data.placements) {
 if(typeof p.key!=='string'||!/^[a-zA-Z][a-zA-Z0-9_.-]{0,119}$/.test(p.key)||seen.has(p.key)||!Array.isArray(p.symbol)||p.symbol.length!==2||p.symbol.some(s=>typeof s!=='string'||!/^[A-Za-z][A-Za-z0-9_]*$/.test(s)||reserved.has(s))||symbols.has(p.symbol.join('.'))||![p.width,p.height].every(x=>Number.isInteger(x)&&x>=1&&x<=8192)) throw Error('Invalid or duplicate placement');
 const bundled='bundledAccessibility' in p?accessibility(p.bundledAccessibility):'';
 seen.add(p.key); symbols.add(p.symbol.join('.')); const [group,name]=p.symbol; if(!groups.has(group)) groups.set(group,[]); groups.get(group).push({ ...p,name,bundled });
}
const lines=['// Generated offline from the checked-in catalog. Do not edit.',`package ${packageName}`,'','import com.assetlib.sdk.AssetRef',...(data.placements.some(p=>'bundledAccessibility' in p)?['import com.assetlib.sdk.AssetAccessibility']:[]),'','object AppAssets {'];
for(const group of [...groups.keys()].sort()) { lines.push(`    object ${group} {`); for(const p of groups.get(group).sort((a,b)=>a.name.localeCompare(b.name,'en'))) lines.push(`        val ${p.name} = AssetRef(${JSON.stringify(p.key)}, ${p.width}, ${p.height}${p.bundled})`); lines.push('    }'); }
lines.push('}',''); const result=lines.join('\n');
if(flag==='--check') { if(readFileSync(output,'utf8')!==result) throw Error('Generated references are stale; run codegen.'); }
else { mkdirSync(dirname(output),{recursive:true}); writeFileSync(output,result); }
