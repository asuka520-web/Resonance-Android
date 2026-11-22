'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {analyze, decodeWav, openDiscogsEmbedder} = require('./probe.cjs');
const {unit, cosine, writeListeningPage} = require('./similarity-pilot.cjs');
const DISCOGS_METADATA_SHA = 'a2e85b2e7372d5f8e0f35bdd6aeae1139f101087d183d0b2fb60b0ea0f01a0ff';

function proposeGenres(classes, windows) {
  const top = scores => classes.map((label, index) => ({label, index, score: scores[index]}))
    .filter(({label, score}) => score > 0 && !label.startsWith('Non-Music---'))
    .sort((left, right) => right.score - left.score).slice(0, 5);
  const [first, second] = windows.map(top);
  const shared = new Set(second.map(entry => entry.index));
  return first.filter(entry => shared.has(entry.index)).map(({label, index}) => {
    const [family, style] = label.split('---');
    return {family, style, score: (windows[0][index] + windows[1][index]) / 2};
  }).sort((left, right) => right.score - left.score).slice(0, 3);
}

function fitPairs(pairs) {
  const positives = pairs.filter(row => row.same).length;
  if (!positives || positives === pairs.length) throw Error('Training needs both same-genre and different-genre examples');
  const width = pairs[0].features.length;
  const mean = Array.from({length: width}, (_, i) => pairs.reduce((sum, row) => sum + row.features[i], 0) / pairs.length);
  const scale = mean.map((average, i) => Math.sqrt(pairs.reduce((sum, row) => sum + (row.features[i] - average) ** 2, 0) / pairs.length) || 1);
  const examples = pairs.map(row => ({same: Number(row.same),
    weight: 0.5 / (row.same ? positives : pairs.length - positives),
    x: row.features.map((value, i) => (value - mean[i]) / scale[i])}));
  const weights = Array(width).fill(0);
  let bias = 0;
  for (let epoch = 0; epoch < 160; epoch++) {
    const gradient = weights.map(weight => 0.02 * weight);
    let intercept = 0;
    for (const example of examples) {
      const predicted = 1 / (1 + Math.exp(-bias - cosine(weights, example.x)));
      const error = (predicted - example.same) * example.weight;
      intercept += error;
      for (let i = 0; i < width; i++) gradient[i] += error * example.x[i];
    }
    bias -= 0.15 * intercept;
    for (let i = 0; i < width; i++) weights[i] -= 0.15 * gradient[i];
  }
  return {bias, weights, mean, scale, pairs: pairs.length, sameGenrePairs: positives};
}

function features(left, right) {
  return [cosine(left.musicnn, right.musicnn), cosine(left.discogs, right.discogs)];
}

function buildTrainingPairs(clips) {
  return clips.flatMap((anchor, index) => clips.slice(index + 1)
    .filter(candidate => candidate.track.artist !== anchor.track.artist)
    .map(candidate => ({same: anchor.track.tag === candidate.track.tag, features: features(anchor, candidate)})));
}

function evaluate(tracks) {
  const all = tracks.flatMap(track => track.windows.map((window, index) => ({...window, track, index})));
  const result = {queries: 0, musicnnTop1: 0, discogsTop1: 0, trainedTop1: 0, musicnnTop3: 0, discogsTop3: 0, trainedTop3: 0};
  const cases = [];
  const folds = new Map();
  for (const anchor of all.filter(clip => clip.track.language === 'English')) {
    const otherArtists = all.filter(candidate => candidate.track.artist !== anchor.track.artist);
    if (!otherArtists.some(candidate => candidate.track.tag === anchor.track.tag)) continue;
    if (!folds.has(anchor.track.artist)) folds.set(anchor.track.artist, fitPairs(buildTrainingPairs(otherArtists)));
    const model = folds.get(anchor.track.artist);
    const candidates = otherArtists.filter(candidate => candidate.index === anchor.index);
    const ranked = candidates.map(candidate => {
      const scores = features(anchor, candidate);
      const input = scores.map((score, i) => (score - model.mean[i]) / model.scale[i]);
      return {track: candidate.track, musicnn: scores[0], discogs: scores[1], trained: model.bias + cosine(model.weights, input)};
    });
    result.queries++;
    const order = key => ranked.slice().sort((left, right) => right[key] - left[key]);
    for (const key of ['musicnn', 'discogs', 'trained']) {
      const list = order(key);
      result[key + 'Top1'] += Number(list[0].track.tag === anchor.track.tag);
      result[key + 'Top3'] += Number(list.slice(0, 3).some(item => item.track.tag === anchor.track.tag));
    }
    if (anchor.index !== 0 || cases.length >= 8) continue;
    const nearest = order('discogs').find(item => item.track.tag === anchor.track.tag);
    const learned = order('trained').find(item => item.track.tag === anchor.track.tag);
    if (nearest.track.id === learned.track.id) continue;
    const id = cases.length + 1;
    const swap = id % 2 === 0;
    cases.push({id, kind: 'similar', anchors: [anchor.track.id],
      a: (swap ? learned : nearest).track.id, b: (swap ? nearest : learned).track.id, audioOption: swap ? 'a' : 'b'});
  }
  return {result, cases, fit: fitPairs(buildTrainingPairs(all))};
}

function writeGenrePage(destination, rows) {
  const escape = value => String(value).replace(/[&<>"']/g, character => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'})[character]);
  const content = rows.map(row => '<tr><th scope="row">' + escape(row.title) + '<br><small>' + escape(row.artist) +
    '</small></th><td>' + escape(row.tag) + '</td><td>' + escape(row.suggestions.map(tag => `${tag.family} · ${tag.style}`).join(' / ') || '两段没有一致候选') + '</td></tr>').join('');
  fs.writeFileSync(destination, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<title>共鸣 · 模型曲风候选</title><style>body{font:100%/1.6 system-ui,sans-serif;max-width:85ch;margin:2rem auto;padding:0 1rem}th,td{text-align:left;padding:.5rem;border-bottom:1px solid #aaa}table{border-collapse:collapse;width:100%}</style>' +
    '<h1>模型曲风候选</h1><p>每首歌最多列三个在两段里都出现的曲风。原有曲库标签单独列出，供对照。</p><table><thead><tr><th>歌曲</th><th>曲库标签</th><th>模型推测</th></tr></thead><tbody>' + content + '</tbody></table></html>');
}

async function main() {
  const [manifest, musicnn, musicnnMetadata, discogs, discogsMetadata] = process.argv.slice(2);
  if (!discogsMetadata) throw Error('Usage: node train-clips.cjs MANIFEST.json MUSICNN.onnx MUSICNN.json DISCOGS.onnx DISCOGS.json');
  const model = fs.readFileSync(discogsMetadata);
  if (createHash('sha256').update(model).digest('hex') !== DISCOGS_METADATA_SHA) throw Error('Discogs metadata checksum mismatch');
  const {classes} = JSON.parse(model);
  const directory = path.dirname(path.resolve(manifest));
  const tracks = [];
  const embedder = await openDiscogsEmbedder(discogs);
  try {
    for (const track of JSON.parse(fs.readFileSync(manifest, 'utf8'))) {
      const windows = [];
      const styles = [];
      for (const clip of track.clips.slice(0, 2)) {
        const samples = decodeWav(fs.readFileSync(path.join(directory, clip)), 0, 15);
        const newer = await embedder.extract(samples, true);
        const original = await analyze(samples, musicnn, musicnnMetadata, false, true);
        if (!newer || !original.embedding) continue;
        windows.push({musicnn: unit(original.embedding), discogs: unit(newer.embedding)});
        styles.push(newer.styles);
      }
      if (windows.length === 2 && windows.every(window => window.musicnn && window.discogs)) {
        tracks.push({...track, windows, suggestions: proposeGenres(classes, styles)});
      }
    }
  } finally { await embedder.close(); }

  const {result, cases, fit} = evaluate(tracks);
  const modelName = 'clip-similarity-ranker-v1';
  const catalogue = tracks.map(({windows, ...track}) => track);
  const labels = catalogue.map(({id, artist, title, tag, suggestions}) => ({id, artist, title, tag, suggestions}));
  const coverage = labels.filter(row => row.suggestions.length).length;
  const summary = {model: modelName, genreModel: 'discogs-effnet-bsdynamic-1', tracks: tracks.length, englishQueriesWithOtherArtistMatch: result,
    perSongGenreCandidates: coverage, blindCases: cases.length, trainingPairs: fit.pairs, sameGenrePairs: fit.sameGenrePairs};
  fs.writeFileSync(path.join(directory, 'clip-ranking-model.json'), JSON.stringify({model: modelName, evidence: ['musicnn-cosine', 'discogs-cosine'], ...fit}, null, 2));
  fs.writeFileSync(path.join(directory, 'clip-training-report.json'), JSON.stringify({...summary, cases, labels}, null, 2));
  writeGenrePage(path.join(directory, 'model-genre-labels.html'), labels);
  writeListeningPage(path.join(directory, 'blind-listening-trained.html'), {model: modelName, cases, catalogue});
  console.log(JSON.stringify(summary, null, 2));
}

module.exports = {fitPairs, evaluate, proposeGenres, DISCOGS_METADATA_SHA};
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
