/**
 * Application entry point.
 *
 * Bootstraps the DB, router, and routes.
 * Flow: auth → keygen (if first device) → chat
 */

import './styles/index.css';

import { getDB } from './db/indexeddb';
import { getRouter } from './components/router';
import { validate } from './api/identity';
import { renderAuthPage } from './pages/auth';
import { renderKeygenPage } from './pages/keygen';
import { renderChatPage } from './pages/chat';

async function main() {
  const db = getDB();
  const router = getRouter();

  // Register routes
  router
    .register('auth', (container) => renderAuthPage(container, db, router))
    .register('keygen', (container) => renderKeygenPage(container, db, router), async (_from, _to) => {
      // Guard: must have session
      const session = await db.getSession();
      if (!session) { router.navigate('auth'); return false; }
      return true;
    })
    .register('chat', (container) => renderChatPage(container, db, router), async (_from, _to) => {
      // Guard: must have session and device
      const session = await db.getSession();
      if (!session) { router.navigate('auth'); return false; }
      const device = await db.getDevice();
      if (!device) { router.navigate('keygen'); return false; }
      return true;
    })
    .setDefault('auth')
    .setFallback('auth');

  // Determine initial route
  const session = await db.getSession();
  if (session) {
    try {
      const validation = await validate(session.token);
      if (validation.valid) {
        const device = await db.getDevice();
        if (device) {
          window.location.hash = '#chat';
        } else {
          window.location.hash = '#keygen';
        }
      } else {
        await db.clearSession();
        window.location.hash = '#auth';
      }
    } catch {
      // Network error or server unreachable — stay on auth
      window.location.hash = '#auth';
    }
  } else {
    window.location.hash = '#auth';
  }

  router.start();
}

main().catch(console.error);
