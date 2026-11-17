'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');
const {performance} = require('node:perf_hooks');
const MODEL_SHA256 = '49668ffec47e52e94b96f45930bb46a28a1368d4bdfb5c05378fa834aca616e1';

function decodeWav(buffer, offset = 0, seconds = 30) {
  if (buffer.length < 44 || buffer.toString('ascii',0,4) !== 'RIFF' || buffer.toString('ascii',8,12) !== 'WAVE') throw Error('Expected PCM WAV');
  let format, samples;
  for (let p=12; p+8<=buffer.length;) {
    const size=buffer.readUInt32LE(p+4), start=p+8;
    if(start+size>buffer.length) throw Error('Truncated WAV');
    const id=buffer.toString('ascii',p,p+4);
    if(id==='fmt ' && size>=16) format={kind:buffer.readUInt16LE(start),channels:buffer.readUInt16LE(start+2),rate:buffer.readUInt32LE(start+4),bits:buffer.readUInt16LE(start+14)};
    if(id==='data') samples=buffer.subarray(start,start+size);
    p=start+size+(size%2);
  }
  if(!format || !samples || format.kind!==1 || format.channels!==1 || format.rate!==16000 || format.bits!==16) throw Error('Convert input to 16 kHz mono PCM16 WAV first');
  const begin=Math.floor(offset*16000), count=Math.min(Math.floor(seconds*16000),Math.floor(samples.length/2)-begin);
  if(count<48000) throw Error('At least three seconds of audio required');
  return Float32Array.from({length:count},(_,i)=>samples.readInt16LE((begin+i)*2)/32768);
}

async function analyze(signal, modelPath, metadataPath, synthetic=false) {
  if(signal.length<48000 || signal.length>480000) throw Error('Use a 3–30 second clip');
  const rms=Math.sqrt(signal.reduce((s,x)=>s+x*x,0)/signal.length);
  const identity={source:'audio-model-prediction',verified:false,updatesCatalogue:false,sampleKind:synthetic?'synthetic-smoke':'local-wav',seconds:signal.length/16000};
  if(rms<0.0001)return {...identity,status:'insufficient_signal',predictions:[]};
  const model=fs.readFileSync(modelPath), hash=crypto.createHash('sha256').update(model).digest('hex');
  if(hash!==MODEL_SHA256)throw Error('Model hash differs from the verified official artifact');
  const metadataBytes=fs.readFileSync(metadataPath);
  if(crypto.createHash('sha256').update(metadataBytes).digest('hex')!=='8e6b3b509f0610c0e65dce467fd459d6777509388eaddb13ed138d8ac1341ffe')throw Error('Model metadata checksum mismatch');
  const metadata=JSON.parse(metadataBytes.toString('utf8'));
  if(metadata.classes?.length!==50)throw Error('Unexpected model metadata');
  const {EssentiaWASM,EssentiaModel}=require('essentia.js');
  const ort=require('onnxruntime-node');
  const start=performance.now();
  const extractor=new EssentiaModel.EssentiaTFInputExtractor(EssentiaWASM,'musicnn');
  const feature=extractor.computeFrameWise(signal,256);
  const patches=[];
  for(let frame=0;frame+187<=feature.frameSize;frame+=93) {
    const patch=new Float32Array(187*96);
    for(let j=0;j<187;j++)patch.set(feature.melSpectrum[frame+j],j*96);
    patches.push(patch);
  }
  extractor.delete();
  if(!patches.length)throw Error('No complete model patch');
  const frontendMs=performance.now()-start;
  const flat=new Float32Array(patches.length*187*96);
  patches.forEach((p,i)=>flat.set(p,i*187*96));
  const loadStart=performance.now();
  const session=await ort.InferenceSession.create(model,{executionProviders:['cpu'],intraOpNumThreads:2});
  const loadMs=performance.now()-loadStart;
  const inferenceStart=performance.now();
  const result=await session.run({melspectrogram:new ort.Tensor('float32',flat,[patches.length,187,96])});
  const scores=result.activations;
  if(scores.dims[1]!==50 || [...scores.data].some(x=>!Number.isFinite(x)))throw Error('Invalid model result');
  const averages=metadata.classes.map((tag,index)=>({tag,modelScore:patches.reduce((sum,_,p)=>sum+scores.data[p*50+index],0)/patches.length}));
  const predictions=averages.sort((a,b)=>b.modelScore-a.modelScore).slice(0,10);
  const report={...identity,status:synthetic?'technical_smoke_only':'experimental_predictions',model:'msd-musicnn-1',modelSha256:hash,
    modelLicense:'CC BY-NC-SA 4.0',patches:patches.length,frontendMs,loadMs,inferenceMs:performance.now()-inferenceStart,predictions,
    limitations:['Scores are uncalibrated model activations, not accuracy or verified genres.','This model mixes genre, mood, instrument, era and voice tags.','No Chinese-song accuracy or Android battery/latency claim has been validated.']};
  await session.release();
  return report;
}

async function main() {
  const args=process.argv.slice(2);
  const option=(name,fallback)=>{const index=args.indexOf(name);return index<0?fallback:args[index+1];};
  const model=option('--model'), metadata=option('--metadata'), output=option('--out');
  if(!model || !metadata)throw Error('Usage: node probe.cjs --model MODEL.onnx --metadata MODEL.json (--wav INPUT.wav | --self-test) [--out REPORT.json]');
  const synthetic=args.includes('--self-test');
  const seconds=Number(option('--seconds','30')),offset=Number(option('--offset','0'));
  if(!Number.isFinite(seconds) || seconds<3 || seconds>30 || !Number.isFinite(offset) || offset<0)throw Error('Invalid clip range');
  let signal;
  if(synthetic)signal=Float32Array.from({length:96000},(_,i)=>0.1*Math.sin(2*Math.PI*440*i/16000));
  else {
    const wav=option('--wav');
    if(!wav || fs.statSync(wav).size>100*1024*1024)throw Error('Provide a local WAV smaller than 100 MiB');
    signal=decodeWav(fs.readFileSync(wav),offset,seconds);
  }
  const report=await analyze(signal,model,metadata,synthetic);
  if(output)fs.writeFileSync(output,JSON.stringify(report,null,2));
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
}
module.exports={decodeWav,analyze};
if(require.main===module)main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
