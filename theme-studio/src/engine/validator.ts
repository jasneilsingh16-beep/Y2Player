import Ajv2020 from 'ajv/dist/2020'
import schema from '../../../docs/skins/skin-v2.schema.json'
import { compileSkin } from './compiler'
import type { EditorError, RenderAsset, SkinDocument, SkinNode } from './types'

const ajv=new Ajv2020({allErrors:true,strict:false})
const structural=ajv.compile(schema)
const actions=new Set(['','confirm','back','home','nowPlaying','playPause','next','previous','left','right','volumeUp','volumeDown','shuffle','repeat','nextGroup','previousGroup'])
const conditions=new Set(['always','focused','unfocused','active','unavailable','playing','paused','hasTrack','noTrack','message','alphabet','emptyRows','pressed','disabled','enabled','charging'])
const bindings=new Set(['screen.title','track.title','track.artist','track.album','playback.elapsed','playback.duration','playback.status','battery','position','row.title','row.subtitle','row.number','row.trailing','search.query','fm.frequency','fm.status','message','alphabet','empty.message','playback.details','volume','volume.mode','shuffle','repeat','track.codec','track.sampleRate','track.bitDepth','track.channels','track.bitrate','device.charging','device.model','device.storageAvailable','display.brightness','clock'])
const reserved=new Set(['classic','classic-light','neon-grid','pixel-garden','studio-deck'])
export const safeAsset=(path:string)=>!!path&&path.length<=128&&!path.startsWith('/')&&!path.includes('\\')&&path.split('/').every(p=>p!==''&&p!=='.'&&p!=='..')

export function validateTheme(theme:SkinDocument,assets:Record<string,RenderAsset>={},allowBundled=false):{errors:EditorError[],compiled?:SkinDocument}{
  const errors:EditorError[]=[]
  if(!structural(theme))for(const e of structural.errors??[])errors.push({path:e.instancePath||'skin',message:e.message??'Invalid value',severity:'error'})
  if(reserved.has(theme.id)&&!allowBundled)errors.push({path:'id',message:'Reserved skin ID; choose a unique community ID',severity:'error'})
  let compiled:SkinDocument|undefined
  try{compiled=compileSkin(theme)}catch(e){errors.push({path:'components',message:(e as Error).message,severity:'error'});return {errors}}
  const palette=new Set(Object.keys(theme.colors));const focusByScreen:Record<string,string[]>={}
  const visit=(nodes:SkinNode[],path:string,inRows=false,depth=0)=>{
    if(depth>16)errors.push({path,message:'Expanded nesting exceeds 16',severity:'error'})
    nodes.forEach((n,i)=>{const p=`${path}[${i}]`
      if(n.action&&!actions.has(n.action))errors.push({path:`${p}.action`,message:`Unknown action: ${n.action}`,severity:'error'})
      if(n.when&&!conditions.has(n.when))errors.push({path:`${p}.when`,message:`Unknown condition: ${n.when}`,severity:'error'})
      for(const match of n.text?.matchAll(/\{([^{}]+)\}/g)??[])if(!bindings.has(match[1]))errors.push({path:`${p}.text`,message:`Unknown binding: ${match[1]}`,severity:'error'})
      for(const key of ['color','background','borderColor'] as const){const v=n[key];if(v&&!v.startsWith('#')&&!palette.has(v))errors.push({path:`${p}.${key}`,message:`Unknown palette color: ${v}`,severity:'error'})}
      if(n.type==='image'&&(!n.asset||!safeAsset(n.asset)))errors.push({path:`${p}.asset`,message:'Unsafe asset path',severity:'error'})
      if(n.type==='image'&&n.asset&&!assets[n.asset])errors.push({path:`${p}.asset`,message:`Asset not loaded: ${n.asset}`,severity:'warning'})
      if(n.type==='rows'&&inRows)errors.push({path:p,message:'Nested row repeaters are unsupported',severity:'error'})
      if(n.focus){const screen=path.split('.')[1]??path;(focusByScreen[screen]??=[]).push(n.focus.id);if(!n.action&&n.type!=='rows')errors.push({path:`${p}.focus`,message:'Focus requires an action or rows node',severity:'error'})}
      visit(n.children??[],`${p}.children`,inRows||n.type==='rows',depth+1)
    })
  }
  for(const [screen,nodes] of Object.entries(compiled.screens))visit(nodes,`screens.${screen}`)
  for(const [screen,ids] of Object.entries(focusByScreen)){const duplicates=ids.filter((id,i)=>ids.indexOf(id)!==i);if(duplicates.length)errors.push({path:`screens.${screen}`,message:`Duplicate focus IDs: ${[...new Set(duplicates)].join(', ')}`,severity:'error'})}
  if(new Blob([JSON.stringify(theme)]).size>256*1024)errors.push({path:'skin',message:'Manifest exceeds 256 KiB',severity:'error'})
  return {errors,compiled}
}
