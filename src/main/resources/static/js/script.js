/* ═══════════════════════════════════════════════════════════════
   Lumina AI — Frontend Script
   Features:
     • Send messages via POST /api/chat (JSON response)
     • Markdown rendering with marked.js
     • Syntax highlighting with highlight.js
     • Copy-to-clipboard for code blocks
     • Typing indicator (animated dots)
     • Auto-resize textarea
     • Character counter
     • Dark / Light theme toggle (persisted in localStorage)
     • New Chat (clears UI + resets server history)
     • Enter to send, Shift+Enter for newline
   ═══════════════════════════════════════════════════════════════ */

// ── marked.js configuration ──────────────────────────────────────
marked.setOptions({
    breaks:   true,   // \n → <br>
    gfm:      true,   // GitHub-flavoured markdown
    highlight: function(code, lang) {
        if (lang && hljs.getLanguage(lang)) {
            return hljs.highlight(code, { language: lang }).value;
        }
        return hljs.highlightAuto(code).value;
    }
});

// ── DOM refs ──────────────────────────────────────────────────────
const messagesEl     = document.getElementById('messages');
const chatArea       = document.getElementById('chatArea');
const userInput      = document.getElementById('userInput');
const sendBtn        = document.getElementById('sendBtn');
const charCount      = document.getElementById('charCount');
const welcomeScreen  = document.getElementById('welcomeScreen');
const newChatBtn     = document.getElementById('newChatBtn');
const themeToggle    = document.getElementById('themeToggle');
const sidebarToggle  = document.getElementById('sidebarToggle');
const sidebar        = document.getElementById('sidebar');

let isWaiting = false;   // prevent double-sends while bot is responding

// ── Theme ──────────────────────────────────────────────────────────
function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('lumina-theme', theme);
}

themeToggle.addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-theme');
    applyTheme(current === 'light' ? 'dark' : 'light');
});

// Apply saved theme on load
const savedTheme = localStorage.getItem('lumina-theme') || 'dark';
applyTheme(savedTheme);

// ── Sidebar toggle ────────────────────────────────────────────────
sidebarToggle.addEventListener('click', () => {
    const isMobile = window.innerWidth <= 768;
    if (isMobile) {
        sidebar.classList.toggle('open');
    } else {
        sidebar.classList.toggle('collapsed');
    }
});

// Close sidebar on mobile when clicking outside
document.addEventListener('click', (e) => {
    if (window.innerWidth <= 768
        && sidebar.classList.contains('open')
        && !sidebar.contains(e.target)
        && e.target !== sidebarToggle) {
        sidebar.classList.remove('open');
    }
});

// ── New Chat ──────────────────────────────────────────────────────
newChatBtn.addEventListener('click', async () => {
    try {
        await fetch('/api/chat/clear', { method: 'POST' });
    } catch (_) { /* ignore network error on clear */ }

    messagesEl.innerHTML = '';
    welcomeScreen.style.display = 'flex';
    userInput.value = '';
    updateSendBtn();
    updateCharCount();
});

// ── Input handling ────────────────────────────────────────────────
userInput.addEventListener('input', () => {
    autoResize();
    updateSendBtn();
    updateCharCount();
});

userInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!sendBtn.disabled) sendMessage();
    }
});

function autoResize() {
    userInput.style.height = 'auto';
    userInput.style.height = Math.min(userInput.scrollHeight, 180) + 'px';
}

function updateSendBtn() {
    const hasText = userInput.value.trim().length > 0;
    sendBtn.disabled = !hasText || isWaiting;
}

function updateCharCount() {
    const len = userInput.value.length;
    charCount.textContent = `${len} / 4000`;
    charCount.style.color = len > 3800
        ? 'var(--danger)'
        : len > 3200
        ? 'var(--warning)'
        : 'var(--text-muted)';
}

sendBtn.addEventListener('click', () => {
    if (!sendBtn.disabled) sendMessage();
});

// ── Fill input from suggestion chips ─────────────────────────────
function fillInput(text) {
    userInput.value = text;
    userInput.focus();
    autoResize();
    updateSendBtn();
    updateCharCount();
    // Close mobile sidebar if open
    if (window.innerWidth <= 768) sidebar.classList.remove('open');
}

// ── Main send function ────────────────────────────────────────────
async function sendMessage() {
    const text = userInput.value.trim();
    if (!text || isWaiting) return;

    // Hide welcome screen on first message
    if (welcomeScreen.style.display !== 'none') {
        welcomeScreen.style.display = 'none';
    }

    // Add user bubble
    appendMessage(text, 'user');
    userInput.value = '';
    autoResize();
    updateSendBtn();
    updateCharCount();

    // Show typing indicator
    isWaiting = true;
    sendBtn.disabled = true;
    const typingEl = showTypingIndicator();

    try {
        const res = await fetch('/api/chat', {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ message: text })
        });

        removeTypingIndicator(typingEl);

        if (!res.ok) {
            const errText = await res.text();
            appendMessage(`⚠️ Server error (${res.status}): ${errText}`, 'bot', true);
            return;
        }

        const data = await res.json();
        appendMessage(data.reply, 'bot');

    } catch (err) {
        removeTypingIndicator(typingEl);
        appendMessage('⚠️ Could not reach Lumina. Please check your connection and try again.', 'bot', true);
        console.error('Fetch error:', err);
    } finally {
        isWaiting = false;
        updateSendBtn();
        userInput.focus();
    }
}

// ── Append a message bubble ───────────────────────────────────────
function appendMessage(text, sender, isError = false) {
    const isBot = sender === 'bot';

    const row = document.createElement('div');
    row.className = `message-row ${sender}`;

    // Avatar
    const avatar = document.createElement('div');
    avatar.className = `avatar ${isBot ? 'bot' : 'user'}`;
    avatar.textContent = isBot ? '✦' : 'You';
    if (!isBot) {
        avatar.style.fontSize = '10px';
        avatar.style.fontWeight = '700';
    }

    // Bubble column
    const col = document.createElement('div');
    col.className = 'bubble-col';

    // Bubble
    const bubble = document.createElement('div');
    bubble.className = `bubble ${isBot ? 'bot' : 'user'}${isError ? ' error' : ''}`;

    if (isBot) {
        // Render markdown for bot messages
        bubble.innerHTML = renderMarkdown(text);
        // Add copy buttons to code blocks
        addCodeCopyButtons(bubble);
        // Syntax highlight any code blocks that aren't already highlighted
        bubble.querySelectorAll('pre code').forEach(block => {
            if (!block.dataset.highlighted) {
                hljs.highlightElement(block);
                block.dataset.highlighted = 'true';
            }
        });
    } else {
        bubble.textContent = text;
    }

    // Timestamp
    const meta = document.createElement('div');
    meta.className = 'msg-meta';
    meta.textContent = getTimestamp();

    col.appendChild(bubble);
    col.appendChild(meta);

    if (isBot) {
        row.appendChild(avatar);
        row.appendChild(col);
    } else {
        row.appendChild(col);
        row.appendChild(avatar);
    }

    messagesEl.appendChild(row);
    scrollToBottom();
}

// ── Markdown rendering ────────────────────────────────────────────
function renderMarkdown(text) {
    // Wrap code blocks with a header div for language label + copy button
    const html = marked.parse(text);

    // Post-process: wrap <pre><code class="language-X"> with our header
    const wrapper = document.createElement('div');
    wrapper.innerHTML = html;

    wrapper.querySelectorAll('pre').forEach(pre => {
        const code = pre.querySelector('code');
        const lang = (code?.className.match(/language-(\w+)/) || [])[1] || 'code';

        const header = document.createElement('div');
        header.className = 'code-header';

        const langLabel = document.createElement('span');
        langLabel.textContent = lang;

        const copyBtn = document.createElement('button');
        copyBtn.className = 'copy-code-btn';
        copyBtn.textContent = 'Copy';
        copyBtn.dataset.code = code?.textContent || '';

        header.appendChild(langLabel);
        header.appendChild(copyBtn);
        pre.insertBefore(header, pre.firstChild);
    });

    return wrapper.innerHTML;
}

// ── Code copy buttons ─────────────────────────────────────────────
function addCodeCopyButtons(container) {
    container.querySelectorAll('.copy-code-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const code = btn.dataset.code;
            navigator.clipboard.writeText(code).then(() => {
                btn.textContent = 'Copied!';
                btn.classList.add('copied');
                setTimeout(() => {
                    btn.textContent = 'Copy';
                    btn.classList.remove('copied');
                }, 2000);
            });
        });
    });
}

// ── Typing indicator ──────────────────────────────────────────────
function showTypingIndicator() {
    const row = document.createElement('div');
    row.className = 'typing-row';
    row.id = 'typingIndicator';

    const avatar = document.createElement('div');
    avatar.className = 'avatar bot';
    avatar.textContent = '✦';

    const bubble = document.createElement('div');
    bubble.className = 'typing-bubble';
    for (let i = 0; i < 3; i++) {
        const dot = document.createElement('div');
        dot.className = 'typing-dot';
        bubble.appendChild(dot);
    }

    row.appendChild(avatar);
    row.appendChild(bubble);

    // Append to chatArea (not #messages) so it appears below messages
    chatArea.appendChild(row);
    scrollToBottom();
    return row;
}

function removeTypingIndicator(el) {
    if (el && el.parentNode) el.parentNode.removeChild(el);
}

// ── Utilities ─────────────────────────────────────────────────────
function scrollToBottom() {
    requestAnimationFrame(() => {
        chatArea.scrollTop = chatArea.scrollHeight;
    });
}

function getTimestamp() {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}