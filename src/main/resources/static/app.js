'use strict';

/*
 * TrustVault front end.
 *
 * Two rules followed throughout:
 *  - every value that came from another user is written with textContent,
 *    never innerHTML, so a document named "<img onerror=...>" is just a name;
 *  - the UI only reflects what the server allows. Hiding a button is a
 *    convenience, never a control.
 */

const state = {
    me: null,
    documents: [],
    shareDocumentId: null
};

const PANELS = {
    documents: 'documentsPanel',
    audit: 'adminPanel',
    people: 'peoplePanel'
};

const $ = (id) => document.getElementById(id);

/* ------------------------------------------------------------------ */
/* HTTP                                                                */
/* ------------------------------------------------------------------ */

function cookieValue(name) {
    const match = document.cookie.match(
        new RegExp('(?:^|;\\s*)' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[1]) : '';
}

async function primeCsrf() {
    await fetch('/api/auth/csrf', { credentials: 'same-origin' });
}

async function request(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();

    const headers = Object.assign(
        { 'Accept': 'application/json' },
        options.headers || {});

    if (method !== 'GET' && method !== 'HEAD') {
        headers['X-XSRF-TOKEN'] = cookieValue('XSRF-TOKEN');
    }

    const init = { method, headers, credentials: 'same-origin' };

    if (options.json !== undefined) {
        headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(options.json);
    }

    if (options.form !== undefined) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        init.body = options.form;
    }

    if (options.body !== undefined) {
        init.body = options.body;
    }

    const response = await fetch(path, init);

    const contentType = response.headers.get('content-type') || '';
    let payload = null;

    if (contentType.includes('application/json')) {
        payload = await response.json().catch(() => null);
    }

    if (!response.ok) {
        const error = new Error(
            (payload && payload.message)
            || 'Request failed (' + response.status + ')');
        error.status = response.status;
        throw error;
    }

    return payload;
}

/* ------------------------------------------------------------------ */
/* Small DOM helpers                                                   */
/* ------------------------------------------------------------------ */

function element(tag, options = {}) {
    const node = document.createElement(tag);

    if (options.text !== undefined) {
        node.textContent = options.text;
    }

    if (options.className) {
        node.className = options.className;
    }

    if (options.title) {
        node.title = options.title;
    }

    if (options.onClick) {
        node.addEventListener('click', options.onClick);
    }

    (options.children || []).forEach((child) => {
        if (child) {
            node.appendChild(child);
        }
    });

    return node;
}

function toast(message, kind = 'info') {
    const node = $('toast');
    node.textContent = message;
    node.className = 'toast ' + kind;
    node.hidden = false;

    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => {
        node.hidden = true;
    }, 4000);
}

function formatDate(value) {
    if (!value) {
        return '';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function formatSize(bytes) {
    if (bytes === null || bytes === undefined) {
        return '';
    }
    if (bytes < 1024) {
        return bytes + ' B';
    }
    if (bytes < 1024 * 1024) {
        return (bytes / 1024).toFixed(1) + ' KB';
    }
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

/* ------------------------------------------------------------------ */
/* Signed-out views                                                    */
/* ------------------------------------------------------------------ */

function setAuthView(view) {

    $('loginForm').hidden = view !== 'login';
    $('registerForm').hidden = view !== 'register';
    $('forgotForm').hidden = view !== 'forgot';

    // "Forgot password" is a branch of signing in, so the Sign in tab stays
    // selected while it is open.
    const activeTab = view === 'register' ? 'register' : 'login';

    document.querySelectorAll('.auth-tab').forEach((tab) => {
        const active = tab.dataset.auth === activeTab;
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    clearAuthMessages();
}

function clearAuthMessages() {
    ['loginError', 'registerError', 'forgotError', 'forgotSuccess']
        .forEach((id) => setMessage(id, ''));
}

function setMessage(id, message) {
    const node = $(id);
    if (!node) {
        return;
    }
    node.textContent = message || '';
    node.hidden = !message;
}

function setBusy(button, busy, busyLabel) {
    if (!button.dataset.label) {
        button.dataset.label = button.textContent.trim();
    }
    button.disabled = busy;
    button.textContent = busy ? busyLabel : button.dataset.label;
}

function wireReveal(buttonId, inputId) {
    const button = $(buttonId);
    const input = $(inputId);

    button.addEventListener('click', () => {
        const showing = input.type === 'text';
        input.type = showing ? 'password' : 'text';
        button.textContent = showing ? 'Show' : 'Hide';
        button.setAttribute(
            'aria-label', showing ? 'Show password' : 'Hide password');
    });
}

async function loadSession() {
    try {
        state.me = await request('/api/users/me');
        await showApp();
    } catch (error) {
        if (error.status === 401) {
            state.me = null;
            showAuth();
            return;
        }
        toast(error.message, 'error');
    }
}

function showAuth() {
    $('authView').hidden = false;
    $('appView').hidden = true;
    $('sessionBar').hidden = true;
    setAuthView('login');
}

async function showApp() {
    $('authView').hidden = true;
    $('appView').hidden = false;
    $('sessionBar').hidden = false;

    $('sessionEmail').textContent = state.me.email;
    $('sessionRole').textContent = state.me.role;

    // Role-based UI visibility
    const isAdmin = state.me.role === 'ADMIN';
    const isOwner = state.me.role === 'OWNER';
    $('adminTab').hidden = !isAdmin;
    $('peopleTab').hidden = !isAdmin;
    if ($('uploadCard')) {
        $('uploadCard').hidden = !isOwner;
    }
    if ($('sharePanel') && !isOwner && !isAdmin) {
        $('sharePanel').hidden = true;
    }

    showPanel('documents');
    await loadDocuments();
}

async function signIn(email, password) {
    const body = 'username=' + encodeURIComponent(email)
        + '&password=' + encodeURIComponent(password);

    await request('/api/auth/login', { method: 'POST', form: body });

    // Sign-in rotates the CSRF token; the old one is now invalid.
    await primeCsrf();
    await loadSession();
}

async function signOut() {
    await request('/api/auth/logout', { method: 'POST' });
    state.me = null;
    state.documents = [];
    await primeCsrf();
    showAuth();
    toast('Signed out');
}

/* ------------------------------------------------------------------ */
/* Panels                                                              */
/* ------------------------------------------------------------------ */

function showPanel(view) {
    const target = PANELS[view] || PANELS.documents;

    Object.values(PANELS).forEach((id) => {
        $(id).hidden = id !== target;
    });

    document.querySelectorAll('.tab').forEach((tab) => {
        const active = tab.dataset.view === view;
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
}

/* ------------------------------------------------------------------ */
/* Documents                                                           */
/* ------------------------------------------------------------------ */

async function loadDocuments(queryString = '') {
    const path = queryString
        ? '/api/documents/search?' + queryString
        : '/api/documents';

    state.documents = await request(path);
    renderDocuments();
}

function renderDocuments() {
    const rows = $('documentRows');
    rows.replaceChildren();

    const summary = state.documents.length === 1
        ? '1 document'
        : state.documents.length + ' documents';

    $('documentSummary').textContent = summary;
    $('emptyState').hidden = state.documents.length > 0;

    state.documents.forEach((document) => {
        rows.appendChild(renderDocumentRow(document));
    });
}

function renderDocumentRow(document) {
    const isOwnerRole = state.me && state.me.role === 'OWNER';
    const isAdminRole = state.me && state.me.role === 'ADMIN';
    const isDocOwner = document.accessLevel === 'OWNER';
    const canEdit = isDocOwner || document.accessLevel === 'EDIT';

    const actions = element('div', { className: 'row-actions' });

    if (!isAdminRole) {
        const download = element('a', {
            className: 'button small',
            text: 'Download',
            onClick: (event) => {
                event.preventDefault();
                downloadDocument(document);
            }
        });
        download.href = '/api/documents/' + document.id + '/download';
        actions.appendChild(download);
    }

    if ((isOwnerRole && isDocOwner) || isAdminRole) {
        actions.appendChild(element('button', {
            text: isAdminRole ? 'Adjust Restrictions' : 'Share',
            className: 'small ghost',
            onClick: () => openShare(document)
        }));
    }

    if (canEdit && !isAdminRole) {
        actions.appendChild(element('button', {
            text: 'Rename',
            className: 'small ghost',
            onClick: () => renameDocument(document)
        }));
    }

    if (isDocOwner && isOwnerRole) {
        actions.appendChild(element('button', {
            text: 'Delete',
            className: 'small danger',
            onClick: () => deleteDocument(document)
        }));
    }

    return element('tr', {
        children: [
            element('td', { text: document.fileName }),
            element('td', { text: document.category }),
            element('td', { text: document.ownerEmail }),
            element('td', { text: formatSize(document.fileSize) }),
            element('td', { text: formatDate(document.uploadedAt) }),
            element('td', {
                children: [element('span', {
                    className: 'badge ' + document.accessLevel.toLowerCase(),
                    text: document.accessLevel
                })]
            }),
            element('td', { children: [actions] })
        ]
    });
}

/*
 * Downloads through fetch so a refused request shows the server's reason as a
 * toast, instead of navigating the tab to a JSON error page where the click
 * looks like it did nothing.
 */
async function downloadDocument(doc) {
    try {
        const response = await fetch(
            '/api/documents/' + doc.id + '/download',
            { credentials: 'same-origin' });

        if (!response.ok) {
            let message = 'Download failed (' + response.status + ')';

            if ((response.headers.get('content-type') || '')
                    .includes('application/json')) {
                const payload = await response.json().catch(() => null);
                if (payload && payload.message) {
                    message = payload.message;
                }
            }

            toast(message, 'error');
            return;
        }

        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = element('a', { text: doc.fileName });

        link.href = url;
        link.download = doc.fileName;
        window.document.body.appendChild(link);
        link.click();
        link.remove();

        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
        toast('Download failed. Please try again.', 'error');
    }
}

async function uploadDocument(file, category) {
    const form = new FormData();
    form.append('file', file);
    form.append('category', category);

    // No Content-Type header: the browser must add the multipart boundary.
    await request('/api/documents/upload', { method: 'POST', body: form });

    toast('Uploaded ' + file.name, 'success');
    await loadDocuments();
}

async function renameDocument(document) {
    const name = window.prompt('New file name', document.fileName);
    if (name === null) {
        return;
    }

    const category = window.prompt('Category', document.category);
    if (category === null) {
        return;
    }

    try {
        await request('/api/documents/' + document.id, {
            method: 'PUT',
            json: { fileName: name, category: category }
        });
        toast('Document updated', 'success');
        await loadDocuments();
    } catch (error) {
        toast(error.message, 'error');
    }
}

async function deleteDocument(document) {
    const confirmed = window.confirm(
        'Delete "' + document.fileName + '"? This cannot be undone.');

    if (!confirmed) {
        return;
    }

    try {
        await request('/api/documents/' + document.id, { method: 'DELETE' });
        toast('Document deleted', 'success');
        if (state.shareDocumentId === document.id) {
            closeShare();
        }
        await loadDocuments();
    } catch (error) {
        toast(error.message, 'error');
    }
}

/* ------------------------------------------------------------------ */
/* Sharing                                                             */
/* ------------------------------------------------------------------ */

async function openShare(document) {
    state.shareDocumentId = document.id;

    $('sharePanel').hidden = false;
    $('shareDocumentName').textContent = document.fileName;
    $('shareEmail').value = '';

    const isAdmin = state.me && state.me.role === 'ADMIN';
    if ($('shareSubmitBtn')) {
        $('shareSubmitBtn').textContent = isAdmin ? 'Adjust / Grant' : 'Grant';
    }

    await loadGrants();
    $('sharePanel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function closeShare() {
    state.shareDocumentId = null;
    $('sharePanel').hidden = true;
}

async function loadGrants() {
    const list = $('grantList');
    list.replaceChildren();

    try {
        const grants = await request(
            '/api/permissions/' + state.shareDocumentId);

        if (grants.length === 0) {
            list.appendChild(element('li', {
                className: 'muted',
                text: 'Nobody else has access.'
            }));
            return;
        }

        grants.forEach((grant) => {
            const revoke = element('button', {
                text: 'Revoke',
                className: 'small danger',
                onClick: async () => {
                    try {
                        await request('/api/permissions/revoke/'
                            + state.shareDocumentId + '/' + grant.userId,
                        { method: 'DELETE' });
                        toast('Access revoked', 'success');
                        await loadGrants();
                    } catch (error) {
                        toast(error.message, 'error');
                    }
                }
            });

            list.appendChild(element('li', {
                children: [
                    element('div', {
                        children: [
                            element('strong', { text: grant.userFullName }),
                            element('span', {
                                className: 'muted',
                                text: ' ' + grant.userEmail
                            }),
                            element('span', {
                                className: 'badge read',
                                text: grant.permissionType
                            })
                        ]
                    }),
                    revoke
                ]
            }));
        });
    } catch (error) {
        toast(error.message, 'error');
    }
}

async function grantAccess(email, level) {
    // Exact-match lookup: the server returns one user or 404, never a list.
    const target = await request(
        '/api/users/lookup?email=' + encodeURIComponent(email));

    await request('/api/permissions/grant/' + state.shareDocumentId, {
        method: 'POST',
        json: { targetUserId: target.id, permissionType: level }
    });

    toast('Access granted to ' + target.email, 'success');
    $('shareEmail').value = '';
    await loadGrants();
    await loadDocuments();
}

/* ------------------------------------------------------------------ */
/* Admin - audit                                                       */
/* ------------------------------------------------------------------ */

async function loadAudit() {
    const params = new URLSearchParams();

    const action = $('auditAction').value;
    const outcome = $('auditOutcome').value;
    const actor = $('auditActor').value.trim();

    if (action) {
        params.set('action', action);
    }
    if (outcome) {
        params.set('outcome', outcome);
    }
    if (actor) {
        params.set('actorEmail', actor);
    }

    const suffix = params.toString() ? '?' + params.toString() : '';
    const entries = await request('/api/admin/audit' + suffix);

    renderAudit(entries);
}

function renderAudit(entries) {
    const rows = $('auditRows');
    rows.replaceChildren();

    if (entries.length === 0) {
        rows.appendChild(element('tr', {
            children: [element('td', {
                text: 'No entries match these filters.',
                className: 'muted'
            })]
        }));
        return;
    }

    entries.forEach((entry) => {
        rows.appendChild(element('tr', {
            children: [
                element('td', { text: String(entry.id) }),
                element('td', { text: formatDate(entry.createdAt) }),
                element('td', {
                    text: entry.actorEmail
                        || (entry.actorUserId ? '#' + entry.actorUserId : 'unknown')
                }),
                element('td', { text: entry.action }),
                element('td', {
                    children: [element('span', {
                        className: 'badge '
                            + (entry.outcome || '').toLowerCase(),
                        text: entry.outcome
                    })]
                }),
                element('td', {
                    text: entry.documentName
                        || (entry.documentId ? '#' + entry.documentId : '')
                }),
                element('td', { text: entry.details || '' }),
                element('td', { text: entry.ipAddress || '' })
            ]
        }));
    });
}

async function verifyChain() {
    try {
        const result = await request('/api/admin/audit/verify');

        $('verifyResult').textContent = result.message;

        toast(result.valid
            ? 'Chain verified across ' + result.checkedEntries + ' entries'
            : 'Chain verification FAILED at #' + result.brokenAtId,
        result.valid ? 'success' : 'error');
    } catch (error) {
        toast(error.message, 'error');
    }
}

/* ------------------------------------------------------------------ */
/* Admin - people                                                      */
/* ------------------------------------------------------------------ */

async function loadPeople() {
    const users = await request('/api/admin/users');
    const rows = $('peopleRows');

    rows.replaceChildren();

    $('tempPasswordBox').hidden = true;

    users.forEach((user) => rows.appendChild(renderPersonRow(user)));
}

function renderPersonRow(user) {
    const isSelf = state.me && user.email === state.me.email;

    const select = element('select', { className: 'role-select' });
    select.disabled = isSelf;
    select.title = isSelf
        ? 'You cannot change your own role'
        : 'Change role for ' + user.email;

    ['ADMIN', 'OWNER', 'VIEWER'].forEach((role) => {
        const option = element('option', { text: role });
        option.value = role;
        option.selected = role === user.role;
        select.appendChild(option);
    });

    select.addEventListener('change', () => changeRole(user, select));

    const reset = element('button', {
        text: 'Issue temp password',
        className: 'small ghost',
        onClick: () => resetPassword(user)
    });
    reset.disabled = isSelf;
    reset.title = isSelf
        ? 'Use your own account settings'
        : 'Issue a one-time password for ' + user.email;

    return element('tr', {
        children: [
            element('td', { text: user.fullName }),
            element('td', { text: user.email }),
            element('td', { children: [select] }),
            element('td', {
                children: [element('div', {
                    className: 'row-actions',
                    children: [reset]
                })]
            })
        ]
    });
}

async function changeRole(user, select) {
    const role = select.value;

    try {
        await request('/api/admin/users/' + user.id + '/role', {
            method: 'PATCH',
            json: { role: role }
        });

        toast(user.email + ' is now ' + role, 'success');
        await loadPeople();

        if (state.me && user.email === state.me.email) {
            await loadSession();
        }
    } catch (error) {
        select.value = user.role;
        toast(error.message, 'error');
    }
}

async function resetPassword(user) {
    const confirmed = window.confirm(
        'Issue a temporary password for ' + user.email + '?\n\n'
        + 'Their current password stops working immediately.');

    if (!confirmed) {
        return;
    }

    try {
        const result = await request(
            '/api/admin/users/' + user.id + '/reset-password',
            { method: 'POST' });

        $('tempEmail').textContent = result.email;
        $('tempPassword').textContent = result.temporaryPassword;
        $('tempPasswordBox').hidden = false;

        toast('Temporary password issued', 'success');
    } catch (error) {
        toast(error.message, 'error');
    }
}

async function copyTemporaryPassword() {
    const value = $('tempPassword').textContent;

    try {
        await navigator.clipboard.writeText(value);
        toast('Copied', 'success');
    } catch (error) {
        // Clipboard access can be refused; select the text so it can be
        // copied by hand instead of silently failing.
        const range = document.createRange();
        range.selectNodeContents($('tempPassword'));
        const selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        toast('Select the password and copy it', 'info');
    }
}

/* ------------------------------------------------------------------ */
/* Wiring                                                              */
/* ------------------------------------------------------------------ */

function searchQuery() {
    const params = new URLSearchParams();

    const keyword = $('searchKeyword').value.trim();
    const category = $('searchCategory').value.trim();
    const owner = $('searchOwner').value.trim();
    const from = $('searchFrom').value;
    const to = $('searchTo').value;
    const scope = $('searchScope').value;

    if (keyword) {
        params.set('keyword', keyword);
    }
    if (category) {
        params.set('category', category);
    }
    if (owner) {
        params.set('owner', owner);
    }
    if (from) {
        params.set('from', from + 'T00:00:00');
    }
    if (to) {
        params.set('to', to + 'T23:59:59');
    }
    if (scope && scope !== 'all') {
        params.set('scope', scope);
    }

    return params.toString();
}

function wire() {

    /* Auth views ---------------------------------------------------- */

    document.querySelectorAll('.auth-tab').forEach((tab) => {
        tab.addEventListener('click', () => setAuthView(tab.dataset.auth));
    });

    $('toRegister').addEventListener('click', () => setAuthView('register'));
    $('toLogin').addEventListener('click', () => setAuthView('login'));
    $('forgotLink').addEventListener('click', () => setAuthView('forgot'));
    $('backToLogin').addEventListener('click', () => setAuthView('login'));

    wireReveal('revealLogin', 'loginPassword');
    wireReveal('revealRegister', 'registerPassword');

    $('loginForm').addEventListener('submit', async (event) => {
        event.preventDefault();

        setMessage('loginError', '');

        const email = $('loginEmail').value.trim();
        const password = $('loginPassword').value;

        if (!email || !password) {
            setMessage('loginError', 'Enter your email and password.');
            return;
        }

        const button = $('loginSubmit');
        setBusy(button, true, 'Signing in…');

        try {
            await signIn(email, password);
            $('loginPassword').value = '';
        } catch (error) {
            setMessage('loginError',
                error.status === 401
                    ? 'Invalid email or password. Please verify your password or reset it below.'
                    : error.message);
            $('loginPassword').select();
        } finally {
            setBusy(button, false);
        }
    });

    $('registerForm').addEventListener('submit', async (event) => {
        event.preventDefault();

        setMessage('registerError', '');

        const fullName = $('registerName').value.trim();
        const email = $('registerEmail').value.trim();
        const password = $('registerPassword').value;

        if (password.length < 12) {
            setMessage('registerError',
                'Choose a password of at least 12 characters.');
            return;
        }

        const button = $('registerSubmit');
        setBusy(button, true, 'Creating…');

        try {
            await request('/api/auth/register', {
                method: 'POST',
                json: { fullName: fullName, email: email, password: password }
            });

            await signIn(email, password);

            $('registerPassword').value = '';
            toast('Account created', 'success');
        } catch (error) {
            setMessage('registerError', error.message);
        } finally {
            setBusy(button, false);
        }
    });

    $('forgotForm').addEventListener('submit', async (event) => {
        event.preventDefault();

        setMessage('forgotError', '');
        setMessage('forgotSuccess', '');

        const email = $('forgotEmail').value.trim();
        const button = $('forgotSubmit');
        setBusy(button, true, 'Sending…');

        try {
            const result = await request('/api/auth/forgot-password', {
                method: 'POST',
                json: { email: email }
            });

            setMessage('forgotSuccess', result.message);
            $('forgotEmail').value = '';
        } catch (error) {
            setMessage('forgotError', error.message);
        } finally {
            setBusy(button, false);
        }
    });

    /* Session ------------------------------------------------------- */

    $('logoutButton').addEventListener('click', async () => {
        try {
            await signOut();
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    /* Documents ----------------------------------------------------- */

    $('uploadForm').addEventListener('submit', async (event) => {
        event.preventDefault();

        const file = $('uploadFile').files[0];
        const category = $('uploadCategory').value.trim();

        if (!file || !category) {
            return;
        }

        try {
            await uploadDocument(file, category);
            $('uploadForm').reset();
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    $('searchForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        try {
            await loadDocuments(searchQuery());
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    $('clearSearch').addEventListener('click', async () => {
        $('searchForm').reset();
        try {
            await loadDocuments();
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    $('shareForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        try {
            await grantAccess($('shareEmail').value.trim(), $('shareLevel').value);
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    /* Admin --------------------------------------------------------- */

    $('verifyButton').addEventListener('click', verifyChain);

    $('auditFilterForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        try {
            await loadAudit();
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    $('clearAudit').addEventListener('click', async () => {
        $('auditFilterForm').reset();
        try {
            await loadAudit();
        } catch (error) {
            toast(error.message, 'error');
        }
    });

    $('copyTempPassword').addEventListener('click', copyTemporaryPassword);

    document.querySelectorAll('.tab').forEach((tab) => {
        tab.addEventListener('click', async () => {
            const view = tab.dataset.view;
            showPanel(view);

            try {
                if (view === 'audit') {
                    await loadAudit();
                }
                if (view === 'people') {
                    await loadPeople();
                }
            } catch (error) {
                toast(error.message, 'error');
            }
        });
    });
}

async function init() {
    wire();
    setAuthView('login');
    await primeCsrf();
    await loadSession();
}

document.addEventListener('DOMContentLoaded', init);
