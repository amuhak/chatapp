import './style.css'
import { renderAuth } from './auth.ts'

const appElement = document.querySelector<HTMLDivElement>('#app')!;

async function init() {
  const token = localStorage.getItem('token');

  if (token) {
    // Validate token
    try {
      const response = await fetch('/api/identity/validate', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      const data = await response.json();
      if (data.valid) {
        localStorage.setItem('username', data.username);
        localStorage.setItem('userUuid', data.userUuid);
        showApp(data.username, data.userUuid);
        return;
      }
    } catch (err) {
      console.error('Failed to validate token', err);
    }
  }

  showAuth();
}

function showAuth() {
  renderAuth(appElement, (_token, username, userUuid) => {
    showApp(username, userUuid);
  });
}

function showApp(username: string, userUuid: string) {
  appElement.innerHTML = `
    <section id="center">
      <div>
        <h1>Welcome, ${username}!</h1>
        <p>User ID: <code>${userUuid}</code></p>
        <p>You are successfully logged in to ChatApp.</p>
      </div>
      <button id="logout-btn" class="counter">Logout</button>
    </section>
  `;

  document.querySelector('#logout-btn')?.addEventListener('click', async () => {
    const token = localStorage.getItem('token');
    if (token) {
      await fetch('/api/identity/logout', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
    }
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('userUuid');
    showAuth();
  });
}


init();
