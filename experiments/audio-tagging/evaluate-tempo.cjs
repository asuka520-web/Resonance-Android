'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {decodeWav, measureRhythm, summarizeTrackTempo} = require('./probe.cjs');

const relativeError = (measured, reference) => Math.abs(measured / reference - 1);

function summarize(windows, field) {
  const available = windows.filter(window => window[field] !== null);
  return {
    accepted: available.length,
    withinFourPercent: available.filter(window => relativeError(window[field], window.referenceBpm) <= 0.04).length,
    halfOrDoubleErrors: available.filter(window => [0.5, 2].some(factor =>
      relativeError(window[field], window.referenceBpm * factor) <= 0.05)).length,
    withheld: windows.length - available.length
  };
}

function compareSameTag(tracks) {
  const eligible = tracks.filter(track => track.tag && track.tempoBpm !== null && Number.isFinite(track.referenceBpm));
  const comparisons = eligible.flatMap(anchor => {
    const sameTag = eligible.filter(candidate => candidate.id !== anchor.id && candidate.tag === anchor.tag);
    if (sameTag.length < 2) return [];
    // Keep the same candidate set in both cases. With equal tags, the baseline uses ID order.
    sameTag.sort((left, right) => left.id.localeCompare(right.id, 'en'));
    const baseline = sameTag[0];
    const closest = sameTag.reduce((best, candidate) =>
      relativeError(candidate.tempoBpm, anchor.tempoBpm) < relativeError(best.tempoBpm, anchor.tempoBpm) ? candidate : best);
    return [{ anchor: anchor.id, baseline: baseline.id, tempo: closest.id,
      baselineErrorBpm: Math.abs(anchor.referenceBpm - baseline.referenceBpm),
      tempoErrorBpm: Math.abs(anchor.referenceBpm - closest.referenceBpm) }];
  });
  const mean = field => comparisons.length ? comparisons.reduce((sum, row) => sum + row[field], 0) / comparisons.length : null;
  return { queries: comparisons.length, baselineMeanErrorBpm: mean('baselineErrorBpm'),
    tempoMeanErrorBpm: mean('tempoErrorBpm'), comparisons };
}

function main() {
  const [manifest, directory] = process.argv.slice(2);
  if (!manifest || !directory) throw Error('Usage: node evaluate-tempo.cjs MANIFEST.json WAV_DIRECTORY');
  const tracks = JSON.parse(fs.readFileSync(manifest, 'utf8')).map(({id, tag, referenceBpm = null}) => {
    const wav = fs.readFileSync(path.join(directory, `${id}.wav`));
    const windows = [0, 15].map(offset => {
      const {status, seconds, trackerBpm, trackerConfidence, beatCount, crossCheckBpm, tempoBpm, tempoCandidates} =
        measureRhythm(decodeWav(wav, offset, 15, 44100));
      const baselineBpm = trackerConfidence > 1.5 && beatCount >= 6 ? trackerBpm : null;
      return {offset, seconds, referenceBpm, status, baselineBpm, crossCheckBpm: crossCheckBpm ?? null, tempoBpm, tempoCandidates};
    });
    return {id, tag, referenceBpm, tempoBpm: summarizeTrackTempo(...windows), windows};
  });
  const referenceWindows = tracks.flatMap(track => track.windows).filter(window => Number.isFinite(window.referenceBpm));
  const referenceTracks = tracks.filter(track => Number.isFinite(track.referenceBpm));
  console.log(JSON.stringify({ referenceWindows: referenceWindows.length,
    before: summarize(referenceWindows, 'baselineBpm'), after: summarize(referenceWindows, 'tempoBpm'),
    trackSummary: summarize(referenceTracks, 'tempoBpm'),
    sameTagTempoComparison: compareSameTag(tracks), tracks }, null, 2));
}

try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
