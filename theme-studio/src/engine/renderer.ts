import { capacity, cell, firstVisible, imageBounds, layoutChildren } from './geometry'
import type { Bounds, PreviewState, RenderAsset, RowData, SkinDocument, SkinNode, Visual } from './types'
export interface HitRegion { path:string; bounds:Bounds; node:SkinNode }
const rows:RowData[]=[
  {title:'Music',subtitle:'Songs, albums, artists and playlists',active:true},
  {title:'Audiobooks',subtitle:'Pick up where you stopped'},
  {title:'Search',subtitle:'Find anything on this device'},
  {title:'Settings',subtitle:'Player and interface options'},
  {title:'FM Radio',subtitle:'Analog receiver'},
  {title:'Now Playing',subtitle:'A Different Kind of Blue'},
  {title:'Diagnostics',subtitle:'System status'}
]
const imageCache=new Map<string,HTMLImageElement>()
const inside=(b:Bounds,x:number,y:number)=>x>=b[0]&&y>=b[1]&&x<b[0]+b[2]&&y<b[1]+b[3]
const intersect=(a:Bounds,b:Bounds):Bounds|null=>{const x=Math.max(a[0],b[0]),y=Math.max(a[1],b[1]),r=Math.min(a[0]+a[2],b[0]+b[2]),d=Math.min(a[1]+a[3],b[1]+b[3]);return r>x&&d>y?[x,y,r-x,d-y]:null}
function roundRect(ctx:CanvasRenderingContext2D,b:Bounds,r=0){ctx.beginPath();ctx.roundRect(b[0],b[1],b[2],b[3],Math.min(r,b[2]/2,b[3]/2))}
function valueColor(theme:SkinDocument,name='primaryText'){return theme.colors[name]??name}
function applyVisual(n:SkinNode,v?:Visual):SkinNode{return v?{...n,...v}:n}

export function renderTheme(canvas:HTMLCanvasElement,theme:SkinDocument,state:PreviewState,assets:Record<string,RenderAsset>,selectedPath=''):HitRegion[]{
  const ctx=canvas.getContext('2d')!, [vw,vh]=theme.viewport, dpr=Math.max(1,window.devicePixelRatio||1)
  canvas.width=Math.round(vw*dpr);canvas.height=Math.round(vh*dpr);canvas.style.aspectRatio=`${vw}/${vh}`
  ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,vw,vh);ctx.fillStyle=valueColor(theme,'background');ctx.fillRect(0,0,vw,vh)
  const regions:HitRegion[]=[]
  const bindings:Record<string,string>={
    'screen.title':state.screen==='main_menu'?'Y2 Player':state.screen.replaceAll('_',' ').replace(/\b\w/g,c=>c.toUpperCase()),
    'track.title':'A Different Kind of Blue','track.artist':'The Evening Quartet','track.album':'After Hours',
    'playback.elapsed':'1:00','playback.duration':'4:00','playback.status':state.playing?'PLAYING':'PAUSED',
    'playback.details':'2026 · Jazz','battery':'82%','position':'1 / 7','search.query':state.query,
    'fm.frequency':'103.7 MHz','fm.status':'FM ON','message':'Theme exported successfully','alphabet':'A',
    'empty.message':state.screen==='search'?'Use the keyboard below to search':'No items here. Press Back to return.',
    'volume':String(state.volume),'volume.mode':'SYSTEM','shuffle':'OFF','repeat':'OFF','track.codec':'FLAC',
    'track.sampleRate':'44100','track.bitDepth':'16','track.channels':'2','track.bitrate':'945000',
    'device.charging':String(state.charging),'device.model':'Y2','device.storageAvailable':'true','display.brightness':'50','clock':'9:41 pm'
  }
  const visible=(n:SkinNode,row?:RowData,index=-1)=>{switch(n.when??'always'){
    case'focused':return index===state.selected;case'unfocused':return index!==state.selected;case'active':return !!row?.active
    case'unavailable':return !!row?.unavailable;case'playing':return state.playing;case'paused':return !state.playing
    case'hasTrack':return true;case'noTrack':return false;case'message':return state.message;case'alphabet':return state.alphabet
    case'emptyRows':return state.empty;case'charging':return state.charging;case'disabled':return !!n.disabled;case'enabled':return !n.disabled
    default:return true}}
  const expand=(text='',row?:RowData,index=-1)=>text.replace(/\{([^{}]+)\}/g,(_,key)=>key==='row.title'?row?.title??'':key==='row.subtitle'?row?.subtitle??'':key==='row.trailing'?row?.trailing??'':key==='row.number'?String(index+1):bindings[key]??'')
  const drawText=(n:SkinNode,b:Bounds,text:string)=>{
    const requested=n.font||theme.font||'sans'
    const family=assets[requested]?.kind==='font'?`"${requested}"`:requested==='sans'||requested==='sans-serif'?'sans-serif':requested
    ctx.font=`${n.bold?'700':'400'} ${n.size??16}px ${family}`;ctx.fillStyle=valueColor(theme,n.color)
    ctx.textAlign=(n.align??'left') as CanvasTextAlign;ctx.textBaseline='middle'
    const x=n.align==='center'?b[0]+b[2]/2:n.align==='right'?b[0]+b[2]:b[0],lineHeight=(n.size??16)*1.18*(n.lineSpacing??1)
    const max=Math.max(1,Math.min(n.lines??1,Math.floor(b[3]/lineHeight))),words=text.split(/(\s+)/),lines:string[]=[];let current=''
    for(const word of words){if(ctx.measureText(current+word).width<=b[2]||!current)current+=word;else{lines.push(current.trim());current=word.trimStart();if(lines.length===max-1)break}}
    if(current&&lines.length<max)lines.push(current.trim());if(!lines.length)lines.push('')
    if(n.overflow!=='clip'&&ctx.measureText(lines.at(-1)??'').width>b[2]){let last=lines.at(-1)??'';while(last&&ctx.measureText(last+'…').width>b[2])last=last.slice(0,-1);lines[lines.length-1]=last+'…'}
    const total=lines.length*lineHeight,start=n.verticalAlign==='top'?b[1]+lineHeight/2:n.verticalAlign==='bottom'?b[1]+b[3]-total+lineHeight/2:b[1]+(b[3]-total)/2+lineHeight/2
    lines.forEach((line,i)=>ctx.fillText(line,x,start+i*lineHeight))
  }
  const drawNodes=(nodes:SkinNode[],ox:number,oy:number,clip:Bounds,path:string,row?:RowData,index=-1)=>{
    nodes.forEach((original,slot)=>{if(!visible(original,row,index))return
      const p=`${path}.${slot}`,focused=index===state.selected
      let n=applyVisual(original,original.states?.normal);n=applyVisual(n,n.states?.[state.playing?'playing':'paused'])
      if(row?.active)n=applyVisual(n,n.states?.active);if(focused)n=applyVisual(n,n.states?.focused);if(n.disabled||row?.unavailable)n=applyVisual(n,n.states?.disabled)
      const b:Bounds=[n.bounds[0]+ox,n.bounds[1]+oy,n.bounds[2],n.bounds[3]],c=intersect(b,clip);if(!c||n.opacity===0)return
      regions.push({path:p,bounds:b,node:original});ctx.save();ctx.beginPath();ctx.rect(c[0],c[1],c[2],c[3]);ctx.clip();ctx.globalAlpha*=n.opacity??1
      const color=valueColor(theme,n.color),background=valueColor(theme,n.background)
      if(n.gradient){const g=n.gradient.direction==='horizontal'?ctx.createLinearGradient(b[0],b[1],b[0]+b[2],b[1]):ctx.createLinearGradient(b[0],b[1],b[0],b[1]+b[3]);g.addColorStop(0,color);g.addColorStop(1,valueColor(theme,n.gradient.endColor));ctx.fillStyle=g}else ctx.fillStyle=color
      if(n.type==='rect'){roundRect(ctx,b,n.radius);n.stroke?(ctx.lineWidth=n.stroke,ctx.strokeStyle=color,ctx.stroke()):ctx.fill()}
      else if(n.type==='circle'){ctx.beginPath();ctx.ellipse(b[0]+b[2]/2,b[1]+b[3]/2,b[2]/2,b[3]/2,0,0,Math.PI*2);n.stroke?(ctx.lineWidth=n.stroke,ctx.strokeStyle=color,ctx.stroke()):ctx.fill()}
      else if(n.type==='line'){ctx.beginPath();ctx.moveTo(b[0],b[1]);ctx.lineTo(b[0]+b[2],b[1]+b[3]);ctx.lineWidth=n.stroke??1;ctx.strokeStyle=color;ctx.stroke()}
      else if(n.type==='path'&&n.points?.length){ctx.beginPath();n.points.forEach(([x,y],i)=>i?ctx.lineTo(b[0]+x*b[2],b[1]+y*b[3]):ctx.moveTo(b[0]+x*b[2],b[1]+y*b[3]));if(n.closed)ctx.closePath();if(n.closed&&!n.stroke)ctx.fill();else{ctx.lineWidth=n.stroke??1;ctx.strokeStyle=color;ctx.stroke()}}
      else if(n.type==='text')drawText(n,b,expand(n.text,row,index))
      else if(n.type==='icon'){ctx.fillStyle=color;ctx.beginPath();ctx.arc(b[0]+b[2]/2,b[1]+b[3]/2,Math.min(b[2],b[3])*.42,0,Math.PI*2);ctx.strokeStyle=color;ctx.lineWidth=1.5;ctx.stroke();drawText({...n,size:Math.min(b[2],b[3])*.55,align:'center'},b,'•')}
      else if(n.type==='artwork'||n.type==='image'){ctx.fillStyle=background;roundRect(ctx,b,n.radius);ctx.fill();const url=n.type==='artwork'?assets.__artwork?.url:(n.asset?assets[n.asset]?.url:undefined);if(url){let img=imageCache.get(url);if(!img){img=new Image();img.src=url;imageCache.set(url,img)}if(img.complete){const d=imageBounds(b,img.naturalWidth,img.naturalHeight,n.imageFit??'stretch',n.imageX,n.imageY);ctx.drawImage(img,d[0],d[1],d[2],d[3])}}}
      else if(n.type==='progress'){ctx.strokeStyle=background;ctx.fillStyle=background;if(n.orientation==='circular'){const d=Math.min(b[2],b[3]),r=d/2-(n.stroke??6)/2;ctx.lineWidth=n.stroke??6;ctx.beginPath();ctx.arc(b[0]+b[2]/2,b[1]+b[3]/2,r,0,Math.PI*2);ctx.stroke();ctx.strokeStyle=color;ctx.beginPath();ctx.arc(b[0]+b[2]/2,b[1]+b[3]/2,r,-Math.PI/2,-Math.PI/2+Math.PI*2*state.progress);ctx.stroke()}else{roundRect(ctx,b,n.radius);ctx.fill();ctx.fillStyle=color;const f=Math.max(0,Math.min(1,state.progress));const fill:Bounds=n.orientation==='vertical'?[b[0],b[1]+b[3]*(1-f),b[2],b[3]*f]:[b[0],b[1],b[2]*f,b[3]];roundRect(ctx,fill,n.radius);ctx.fill()}}
      else if(n.type==='keyboard'){const keys=['QWERTYUIOP','ASDFGHJKL','ZXCVBNM⌫','SPACE CLEAR RESULTS'];const h=b[3]/4;keys.forEach((line,ri)=>{const labels=ri===3?line.split(' '):[...line],w=b[2]/labels.length;labels.forEach((key,ki)=>{const gap=n.keyStyle?.gap??2,kb:Bounds=[b[0]+ki*w+gap,b[1]+ri*h+gap,w-2*gap,h-2*gap];ctx.fillStyle=valueColor(theme,ri===0&&ki===0?n.keyStyle?.focusBackground:n.keyStyle?.background);roundRect(ctx,kb,n.keyStyle?.radius);ctx.fill();drawText({...n,size:n.size??13,color:ri===0&&ki===0?n.keyStyle?.focusColor:n.keyStyle?.color,align:'center'},kb,key)})})}
      else if(n.type==='rows'){if(!state.empty){const data=state.screen==='songs'?[{title:'A Different Kind of Blue',subtitle:'The Evening Quartet',trailing:'4:00',active:true}]:rows,nn={...n,bounds:b};const first=firstVisible(nn,state.selected);for(let s=0;s<Math.min(capacity(nn),data.length-first);s++){const cb=cell(nn,s),ri=first+s;drawNodes(layoutChildren({...n,bounds:cb}),cb[0],cb[1],intersect(cb,c)??c,`${p}.row`,data[ri],ri)}}}
      else if(n.type==='group')drawNodes(layoutChildren(n),b[0],b[1],c,`${p}.children`,row,index)
      if((n.borderWidth??0)>0){ctx.strokeStyle=valueColor(theme,n.borderColor);ctx.lineWidth=n.borderWidth!;roundRect(ctx,b,n.radius);ctx.stroke()}
      ctx.restore()
    })
  }
  const nodes=theme.screens[state.screen]??theme.screens.default
  drawNodes(nodes,0,0,[0,0,vw,vh],`screens.${state.screen}`)
  const selected=regions.find(r=>r.path===selectedPath);if(selected){ctx.save();ctx.strokeStyle='#ffffff';ctx.lineWidth=1;ctx.setLineDash([4,3]);ctx.strokeRect(selected.bounds[0]+.5,selected.bounds[1]+.5,selected.bounds[2]-1,selected.bounds[3]-1);ctx.restore()}
  return regions
}
export const hitRegion=(regions:HitRegion[],x:number,y:number)=>[...regions].reverse().find(r=>inside(r.bounds,x,y))
