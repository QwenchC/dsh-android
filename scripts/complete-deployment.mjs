/**
 * Complete a deployed Harness tree's peer-dependency closure.
 *
 * `pnpm deploy --prod` installs the deployed package's dependency closure. The
 * Harness declares its service definitions as peer dependencies, and a peer of
 * a transitive package is not part of that closure, so the deployment comes out
 * missing packages that every consumer imports — `@deepseek-ai/dsh-sandbox`,
 * `@deepseek-ai/dsh-session-persistence`, and their kin.
 *
 * This script walks the deployed tree, finds every `@deepseek-ai/*` requirement
 * that no installed package satisfies, copies the matching workspace package in,
 * and repeats until nothing new is missing.
 *
 * Usage: node scripts/complete-deployment.mjs <workspace-root> <deploy-dir>
 */
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'

const [workspaceRoot, deployDir] = process.argv.slice(2)
if (!workspaceRoot || !deployDir) {
  console.error('usage: complete-deployment.mjs <workspace-root> <deploy-dir>')
  process.exit(2)
}
const packagesRoot = resolve(workspaceRoot, 'packages')
const vendorRoot = resolve(workspaceRoot, 'vendor')
const modulesRoot = resolve(deployDir, 'node_modules')

/** Every workspace package's manifest, keyed by package name. */
function indexWorkspace() {
  const found = new Map()
  for (const root of [packagesRoot, vendorRoot]) {
    if (!existsSync(root)) continue
    for (const group of readdirSync(root, { withFileTypes: true })) {
      if (!group.isDirectory()) continue
      const groupDir = join(root, group.name)
      for (const entry of readdirSync(groupDir, { withFileTypes: true })) {
        if (!entry.isDirectory()) continue
        const manifestPath = join(groupDir, entry.name, 'package.json')
        if (!existsSync(manifestPath)) continue
        const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
        if (typeof manifest.name === 'string') found.set(manifest.name, dirname(manifestPath))
      }
    }
  }
  return found
}

/** Manifests of every installed package, following one level of scoping. */
function installedManifests() {
  const manifests = []
  if (!existsSync(modulesRoot)) return manifests
  for (const entry of readdirSync(modulesRoot, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    if (entry.name.startsWith('@')) {
      for (const scoped of readdirSync(join(modulesRoot, entry.name), { withFileTypes: true })) {
        const manifestPath = join(modulesRoot, entry.name, scoped.name, 'package.json')
        if (existsSync(manifestPath)) manifests.push(manifestPath)
      }
      continue
    }
    const manifestPath = join(modulesRoot, entry.name, 'package.json')
    if (existsSync(manifestPath)) manifests.push(manifestPath)
  }
  return manifests
}

const workspace = indexWorkspace()
const installed = new Set()
let copied = 0

for (let round = 0; round < 20; round += 1) {
  const manifests = installedManifests()
  installed.clear()
  for (const path of manifests) {
    try {
      installed.add(JSON.parse(readFileSync(path, 'utf8')).name)
    } catch {
      // A malformed manifest is not this script's problem to fix.
    }
  }

  const missing = new Set()
  for (const path of manifests) {
    const manifest = JSON.parse(readFileSync(path, 'utf8'))
    for (const field of ['peerDependencies', 'peerDependenciesMeta', 'dependencies']) {
      const declared = manifest[field]
      if (declared === undefined) continue
      for (const name of Object.keys(declared)) {
        if (!name.startsWith('@deepseek-ai/')) continue
        if (installed.has(name)) continue
        if (!workspace.has(name)) continue
        missing.add(name)
      }
    }
  }
  if (missing.size === 0) break

  for (const name of missing) {
    const source = workspace.get(name)
    const destination = join(modulesRoot, ...name.split('/'))
    mkdirSync(dirname(destination), { recursive: true })
    cpSync(source, destination, {
      recursive: true,
      dereference: true,
      filter: (path) => basename(path) !== 'node_modules' && !path.endsWith('.tsbuildinfo'),
    })
    copied += 1
    console.log(`  + ${name}`)
  }
}

console.log(`complete-deployment: ${copied} workspace package(s) added, ${installed.size} installed`)
