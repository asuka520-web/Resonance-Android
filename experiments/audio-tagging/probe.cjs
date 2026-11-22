'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');
const {performance} = require('node:perf_hooks');
const MODEL_SHA256 = '49668ffec47e52e94b96f45930bb46a28a1368d4bdfb5c05378fa834aca616e1';
const DISCOGS_SHA256 = 'a280825b334797cf677939db8cd5762c0392aedd0ca6415dbc1cd083f045e43c';

const MODEL_SAMPLE_RATE = 16000;
const RHYTHM_SAMPLE_RATE = 44100;
const TEMPO_AGREEMENT = 1.05;

function decodeWav(buffer, offset = 0, seconds = 30, sampleRate = MODEL_SAMPLE_RATE) {
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
  if(!format || !samples || format.kind!==1 || format.channels!==1 || format.rate!==sampleRate || format.bits!==16) throw Error(`Convert input to ${sampleRate / 1000} kHz mono PCM16 WAV first`);
  const begin=Math.floor(offset*sampleRate), count=Math.min(Math.floor(seconds*sampleRate),Math.floor(samples.length/2)-begin);
  if(count<3*sampleRate) throw Error('At least three seconds of audio required');
  return Float32Array.from({length:count},(_,i)=>samples.readInt16LE((begin+i)*2)/32768);
}

function assessTempo(trackerBpm, crossCheckBpm) {
  const evidence = { trackerBpm, crossCheckBpm, tempoBpm: null };
  if (!Number.isFinite(crossCheckBpm) || crossCheckBpm <= 0) {
    return { ...evidence, crossCheckBpm: null, status: 'uncertain_tempo' };
  }
  const tempoCandidates = [trackerBpm, crossCheckBpm].sort((a, b) => a - b);
  const ratio = tempoCandidates[1] / tempoCandidates[0];
  if (ratio <= TEMPO_AGREEMENT) return { ...evidence, status: 'estimated', tempoBpm: trackerBpm };
  if (ratio >= 2 / TEMPO_AGREEMENT && ratio <= 2 * TEMPO_AGREEMENT) {
    return { ...evidence, status: 'ambiguous_half_double_tempo', tempoCandidates };
  }
  return { ...evidence, status: 'uncertain_tempo' };
}

function summarizeTrackTempo(first, second) {
  const confirmed = [first, second].filter(window => window.tempoBpm !== null);
  if (confirmed.length === 0) return null;
  const anchor = confirmed[0].tempoBpm;
  const corroboratingWindow = confirmed[0] === first ? second : first;
  const candidates = corroboratingWindow.tempoBpm === null ? corroboratingWindow.tempoCandidates ?? [] : [corroboratingWindow.tempoBpm];
  const match = candidates.find(bpm => Math.abs(bpm / anchor - 1) <= 0.04);
  return match === undefined ? null : (anchor + match) / 2;
}

function measureRhythm(signal) {
  const seconds = signal.length / RHYTHM_SAMPLE_RATE;
  if (seconds < 3 || seconds > 30) throw Error('Use a 3–30 second clip');
  const rms = Math.sqrt(signal.reduce((sum, sample) => sum + sample * sample, 0) / signal.length);
  const measurement = {
    source: 'local-waveform-measurement',
    updatesCatalogue: false,
    seconds,
    rmsDbfs: rms > 0 ? 20 * Math.log10(rms) : null,
    tempoBpm: null,
    beatIntervalVariation: null
  };
  if (rms < 0.0001) return {...measurement, status: 'insufficient_signal'};
  if (seconds < 12) return {...measurement, status: 'clip_too_short_for_tempo'};

  const {Essentia, EssentiaWASM} = require('essentia.js');
  const core = new Essentia(EssentiaWASM);
  const input = core.arrayToVector(signal);
  try {
    const result = core.RhythmExtractor2013(input);
    try {
      const {bpm, confidence, ticks, bpmIntervals} = result;
      const evidence = {...measurement, beatCount: ticks.size(), trackerConfidence: Number.isFinite(confidence) ? confidence : null,
        trackerBpm: Number.isFinite(bpm) && bpm > 0 ? bpm : null};
      if (!(evidence.trackerConfidence > 1.5 && bpm > 0 && Number.isFinite(bpm) && evidence.beatCount >= 6)) {
        return {...evidence, status: 'uncertain_tempo'};
      }
      const intervals = core.vectorToArray(bpmIntervals);
      const average = intervals.reduce((sum, interval) => sum + interval, 0) / intervals.length;
      const variance = intervals.reduce((sum, interval) => sum + (interval - average) ** 2, 0) / intervals.length;
      const crossCheck = core.PercivalBpmEstimator(input);
      return {...evidence, ...assessTempo(bpm, crossCheck.bpm),
        beatIntervalVariation: average > 0 ? Math.sqrt(variance) / average : null};
    } finally {
      result.ticks.delete();
      result.bpmIntervals.delete();
      result.estimates.delete();
    }
  } finally {
    input.delete();
    core.delete();
  }
}

function musicnnInput(signal, framesPerPatch, patchHop, Tensor) {
  const {EssentiaWASM, EssentiaModel} = require('essentia.js');
  const extractor = new EssentiaModel.EssentiaTFInputExtractor(EssentiaWASM, 'musicnn');
  try {
    const feature = extractor.computeFrameWise(signal, 256);
    const count = Math.floor((feature.frameSize - framesPerPatch) / patchHop) + 1;
    if (count <= 0) throw Error('No complete model patch');
    const flat = new Float32Array(count * framesPerPatch * 96);
    for (let patch = 0; patch < count; patch++) {
      for (let frame = 0; frame < framesPerPatch; frame++) {
        flat.set(feature.melSpectrum[patch * patchHop + frame], (patch * framesPerPatch + frame) * 96);
      }
    }
    return new Tensor('float32', flat, [count, framesPerPatch, 96]);
  } finally {
    extractor.delete();
  }
}

async function analyze(signal, modelPath, metadataPath, synthetic=false, includeEmbedding=false) {
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
  const ort=require('onnxruntime-node');
  const start=performance.now();
  const input = musicnnInput(signal, 187, 93, ort.Tensor);
  const [patchCount] = input.dims;
  const frontendMs=performance.now()-start;
  const loadStart=performance.now();
  const session=await ort.InferenceSession.create(model,{executionProviders:['cpu'],intraOpNumThreads:2});
  const loadMs=performance.now()-loadStart;
  const inferenceStart=performance.now();
  try {
    const result=await session.run({melspectrogram:input});
    const modelScores = meanPatches(result.activations, patchCount, 50);
    const averages=metadata.classes.map((tag,index)=>({tag,modelScore:modelScores[index]}));
    const predictions=averages.sort((a,b)=>b.modelScore-a.modelScore).slice(0,10);
    const report={...identity,status:synthetic?'technical_smoke_only':'experimental_predictions',model:'msd-musicnn-1',modelSha256:hash,
      modelLicense:'CC BY-NC-SA 4.0',patches:patchCount,frontendMs,loadMs,inferenceMs:performance.now()-inferenceStart,predictions,
      limitations:['Scores are uncalibrated model activations, not accuracy or verified genres.','This model mixes genre, mood, instrument, era and voice tags.','No Chinese-song accuracy or Android battery/latency claim has been validated.']};
    if (includeEmbedding) report.embedding = meanPatches(result.embeddings, patchCount, 200);
    return report;
  } finally {
    await session.release();
  }
}

function meanPatches(output, expectedRows, expectedWidth) {
  const {data, dims} = output;
  const [rows, width] = dims;
  if (rows !== expectedRows || width !== expectedWidth || !data.every(Number.isFinite)) throw Error('Invalid model output');
  const result = Array(width).fill(0);
  for (let row = 0; row < rows; row++) {
    for (let index = 0; index < width; index++) result[index] += data[row * width + index];
  }
  return result.map(value => value / rows);
}

async function openDiscogsEmbedder(modelPath) {
  const model = fs.readFileSync(modelPath);
  if (crypto.createHash('sha256').update(model).digest('hex') !== DISCOGS_SHA256) throw Error('Discogs model checksum mismatch');
  const {InferenceSession, Tensor} = require('onnxruntime-node');
  const session = await InferenceSession.create(model, {executionProviders: ['cpu'], intraOpNumThreads: 2});
  return {
    async extract(signal, includeStyles = false) {
      if (!signal.some(value => Math.abs(value) >= 0.0001)) return null;
      const input = musicnnInput(signal, 128, 62, Tensor);
      const {embeddings, activations} = await session.run({melspectrogram: input});
      return {
        embedding: meanPatches(embeddings, input.dims[0], 1280),
        styles: includeStyles ? meanPatches(activations, input.dims[0], 400) : null
      };
    },
    close: () => session.release()
  };
}

async function main() {
  const args=process.argv.slice(2);
  const option=(name,fallback)=>{const index=args.indexOf(name);return index<0?fallback:args[index+1];};
  const model=option('--model'), metadata=option('--metadata'), output=option('--out');
  const measurementsOnly = args.includes('--measure-only');
  const synthetic=args.includes('--self-test');
  if (measurementsOnly && synthetic) throw Error('Use --measure-only with a local 44.1 kHz WAV');
  if (!measurementsOnly && (!model || !metadata)) throw Error('Usage: node probe.cjs --model MODEL.onnx --metadata MODEL.json (--wav INPUT.wav | --self-test) [--out REPORT.json]');
  const seconds=Number(option('--seconds','30')),offset=Number(option('--offset','0'));
  if(!Number.isFinite(seconds) || seconds<3 || seconds>30 || !Number.isFinite(offset) || offset<0)throw Error('Invalid clip range');
  let signal;
  if(synthetic)signal=Float32Array.from({length:96000},(_,i)=>0.1*Math.sin(2*Math.PI*440*i/16000));
  else {
    const wav=option('--wav');
    if(!wav || fs.statSync(wav).size>100*1024*1024)throw Error('Provide a local WAV smaller than 100 MiB');
    const sampleRate = measurementsOnly ? RHYTHM_SAMPLE_RATE : MODEL_SAMPLE_RATE;
    signal=decodeWav(fs.readFileSync(wav),offset,seconds,sampleRate);
  }
  const report=measurementsOnly ? measureRhythm(signal) : await analyze(signal,model,metadata,synthetic);
  if(output)fs.writeFileSync(output,JSON.stringify(report,null,2));
  process.stdout.write(JSON.stringify(report,null,2)+'\n');
}
module.exports={decodeWav,analyze,measureRhythm,assessTempo,summarizeTrackTempo,openDiscogsEmbedder};
if(require.main===module)main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
