import { execFileSync, spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '../..')
const port = process.env.E2E_PORT ?? '3790'
const rcPort = process.env.E2E_RC_PORT ?? '8890'
const bin = path.join(root, 'server/build/install/server/bin/server')
if (!fs.existsSync(bin)) {
  console.error(`${bin} is missing; run ./gradlew :server:installDist after npm run build -w web`)
  process.exit(1)
}

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'ore-e2e-'))
fs.mkdirSync(path.join(work, 'rules'))
fs.mkdirSync(path.join(work, 'data'))
const gol = path.join(work, 'opava-centre.gol')
fs.copyFileSync(path.join(here, 'fixtures/opava-centre.gol'), gol)

const container = `ore-e2e-${process.pid}`
let dbUrl = process.env.E2E_DB_URL
let ownDb = false
let server = null
let stopping = false

function stop(code = 0) {
  if (stopping) return
  stopping = true
  if (server && server.exitCode === null) server.kill('SIGTERM')
  if (ownDb) {
    try {
      execFileSync('docker', ['rm', '-f', container], { stdio: 'ignore' })
    } catch {}
  }
  fs.rmSync(work, { recursive: true, force: true })
  process.exit(code)
}
for (const sig of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(sig, () => stop(0))

if (!dbUrl) {
  ownDb = true
  execFileSync('docker', ['run', '-d', '--rm', '--name', container, '-e', 'POSTGRES_USER=osmreview', '-e', 'POSTGRES_PASSWORD=osmreview', '-e', 'POSTGRES_DB=osmreview', '-p', '127.0.0.1::5432', 'postgres:18'], { stdio: 'ignore' })
  const mapped = execFileSync('docker', ['port', container, '5432/tcp']).toString().trim().split('\n')[0]
  const hostPort = mapped.split(':').at(-1)
  const deadline = Date.now() + 60000
  for (;;) {
    try {
      execFileSync('docker', ['exec', container, 'pg_isready', '-U', 'osmreview', '-h', '127.0.0.1'], { stdio: 'ignore' })
      execFileSync('docker', ['exec', container, 'psql', '-U', 'osmreview', '-h', '127.0.0.1', '-c', 'select 1'], { stdio: 'ignore' })
      break
    } catch {
      if (Date.now() > deadline) {
        console.error('Postgres did not start')
        stop(1)
      }
      await new Promise((r) => setTimeout(r, 500))
    }
  }
  dbUrl = `jdbc:postgresql://127.0.0.1:${hostPort}/osmreview`
}

server = spawn(bin, [], {
  cwd: work,
  stdio: 'inherit',
  env: {
    ...process.env,
    PORT: port,
    REMOTE_CONTROL_PORT: rcPort,
    PUBLIC_URL: `http://localhost:${port}`,
    GOL_FILE: gol,
    DB_URL: dbUrl,
    DB_USER: process.env.E2E_DB_USER ?? 'osmreview',
    DB_PASSWORD: process.env.E2E_DB_PASSWORD ?? 'osmreview',
    RULES_DIR: path.join(work, 'rules'),
    DATA_DIR: path.join(work, 'data'),
    OSM_TARGET: 'dev',
    OVERPASS_URL: 'http://127.0.0.1:9/interpreter',
  },
})
server.on('exit', (code) => stop(code ?? 1))
