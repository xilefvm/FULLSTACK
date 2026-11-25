import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {performance} from 'node:perf_hooks';
import {setTimeout as delay} from 'node:timers/promises';

const [address = 'http://127.0.0.1:8080', rowArg = '250000', recoveryProject] = process.argv.slice(2);
const base = new URL(address);
const rows = Number(rowArg);
if (!Number.isInteger(rows) || rows < 5000 || rows > 275000) {
  throw new Error('Choose an integer between 5000 and 275000 rows.');
}
if (recoveryProject) {
  if (!/^dataclean-bench-[a-z0-9-]+$/.test(recoveryProject) || base.hostname !== '127.0.0.1'
      || compose('port', 'app', '8080') !== '127.0.0.1:' + base.port) {
    throw new Error('Recovery requires a dedicated dataclean-bench-* Compose project on the supplied loopback port.');
  }
}

const schema = 'benchmark';
const text = 'x'.repeat(64);
const csv = ['id,value,region\n'];
for (let index = 1; index <= rows; index++) {
  csv.push(index + ',' + text + ',' + (index % 10 === 0 ? 'XX' : 'ES') + '\n');
}
const file = new Blob(csv, {type: 'text/csv'});
if (file.size > 20 * 1024 * 1024) throw new Error('Generated CSV exceeds 20 MiB.');

await waitForApplication();
await request('/api/schemas/' + schema + '/versions/1', {
  method: 'PUT',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({columns: [
    {name: 'id', type: 'INTEGER', required: true, unique: true},
    {name: 'value', type: 'STRING', required: true, maxLength: 80},
    {name: 'region', type: 'STRING', allowedValues: ['ES', 'PT']},
  ]}),
});

const baseline = await measure(await submit());
console.log(JSON.stringify({rows, bytes: file.size, baseline}, null, 2));

if (recoveryProject) {
  let stoppedWorker;
  let probeInstalled = false;
  try {
    sql(`
      CREATE FUNCTION dataclean_benchmark_pause() RETURNS trigger LANGUAGE plpgsql AS
      'BEGIN PERFORM pg_sleep(45); RETURN NEW; END';
      CREATE TRIGGER dataclean_benchmark_pause BEFORE INSERT ON row_issue
      FOR EACH ROW EXECUTE FUNCTION dataclean_benchmark_pause();
    `);
    probeInstalled = true;
    const run = await submit();
    const active = await until(() =>
      sql("SELECT pid, host(client_addr) FROM pg_stat_activity WHERE wait_event = 'PgSleep' AND query LIKE '%INSERT INTO row_issue%' LIMIT 1"),
    30000);
    const [processId, address] = active.split('|');
    const backend = Number(processId);
    if (!Number.isInteger(backend) || backend < 1 || !address) throw new Error('Invalid benchmark backend.');
    const workers = compose('ps', '--quiet', 'worker').split(/\r?\n/).filter(Boolean);
    const worker = workers.find(id => docker('inspect', '--format',
      '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}', id) === address);
    if (!worker) throw new Error('The active database connection does not belong to a worker in this project.');
    const interrupted = await (await request('/api/runs/' + run.id)).json();
    if (interrupted.state !== 'RUNNING') throw new Error('Run was not in progress at the interruption.');
    docker('kill', '--signal', 'SIGKILL', worker);
    stoppedWorker = worker;
    // PostgreSQL can keep a sleeping statement alive after the client container stops.
    sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid = " + backend
      + " AND wait_event = 'PgSleep' AND query LIKE '%INSERT INTO row_issue%'");
    removeProbe();
    probeInstalled = false;
    if (workers.length === 1) {
      docker('start', stoppedWorker);
      stoppedWorker = undefined;
    }
    await request('/actuator/health');
    const recovered = await measure(run);
    if (recovered.reportSha256 !== baseline.reportSha256) throw new Error('Recovered report differs from baseline.');
    console.log(JSON.stringify({recovery: 'matching report after forced interruption', workerReplicas: workers.length,
      survivorProcessedRun: workers.length > 1, recovered}, null, 2));
  } finally {
    try {
      if (probeInstalled) removeProbe();
    } finally {
      if (stoppedWorker) docker('start', stoppedWorker);
    }
  }
}

function compose(...args) {
  return docker('compose', '-p', recoveryProject, ...args);
}

function docker(...args) {
  return execFileSync('docker', args, {
    encoding: 'utf8', timeout: 20000,
  }).trim();
}

function sql(statement) {
  return compose('exec', '-T', 'db', 'psql', '-XAtq', '-v', 'ON_ERROR_STOP=1', '-U', 'dataclean',
    '-d', 'dataclean', '-c', statement);
}

function removeProbe() {
  sql('DROP TRIGGER dataclean_benchmark_pause ON row_issue; DROP FUNCTION dataclean_benchmark_pause();');
}

async function request(route, options, timeout = 15000) {
  const response = await fetch(new URL(route, base), {...options, signal: AbortSignal.timeout(timeout)});
  if (!response.ok) throw new Error(route + ' returned ' + response.status + ': ' + await response.text());
  return response;
}

async function submit() {
  const data = new FormData();
  data.set('schemaName', schema);
  data.set('schemaVersion', '1');
  data.set('file', file, 'benchmark.csv');
  const started = performance.now();
  const {id} = await (await request('/api/runs', {method: 'POST', body: data}, 90000)).json();
  return {id, started};
}

async function until(probe, timeout) {
  const deadline = performance.now() + timeout;
  while (performance.now() < deadline) {
    const result = await probe();
    if (result) return result;
    await delay(250);
  }
  throw new Error('Timed out waiting for benchmark condition.');
}

async function waitForApplication() {
  await until(async () => {
    try { return (await request('/actuator/health')).ok; } catch { return false; }
  }, 30000);
}

async function measure({id, started}) {
  let sampledApiHeapBytes = 0;
  const run = await until(async () => {
    const result = await (await request('/api/runs/' + id)).json();
    if (result.state === 'FAILED') throw new Error(result.error);
    const memory = await (await request('/actuator/metrics/jvm.memory.used?tag=area:heap')).json();
    sampledApiHeapBytes = Math.max(sampledApiHeapBytes, memory.measurements[0].value);
    return result.state === 'COMPLETED' ? result : null;
  }, 180000);
  const latencyMs = Math.round(performance.now() - started);
  if (run.totalRows !== rows || run.invalidRows !== Math.floor(rows / 10)) throw new Error('Unexpected row counts.');
  const hash = createHash('sha256');
  const report = await request('/api/runs/' + id + '/issues.csv');
  for await (const chunk of report.body) hash.update(chunk);
  const processingMs = Date.parse(run.finishedAt) - Date.parse(run.attemptStartedAt);
  return {
    latencyMs,
    queueAndPreviousAttemptsMs: Date.parse(run.attemptStartedAt) - Date.parse(run.submittedAt),
    processingMs,
    rowsPerSecond: Math.round(rows * 1000 / Math.max(1, processingMs)),
    sampledApiHeapBytes,
    reportSha256: hash.digest('hex'),
  };
}
