// Lays out the model the extension posts (src/core/dashboard.ts, src/core/statistics.ts) and draws the meter, the
// daily chart and the monthly chart to the view's width, as the IntelliJ plugin's UsageMeter and DailyChart paint them.
(function () {
    const vscode = acquireVsCodeApi();
    const SVG = 'http://www.w3.org/2000/svg';
    const page = document.getElementById('page');
    const byline = document.getElementById('byline');
    const accountRow = document.getElementById('account-row');
    const accountSelect = document.getElementById('account');
    const tabs = document.getElementById('tabs');
    let model = null;
    let hovered = -1;
    /** The month and the segment under the pointer in the monthly chart: { bar, segment } or null. */
    let hoveredMonth = null;
    /** What is typed in the Sessions text box: kept across renders. */
    let sessionsQuery = '';

    for (const button of document.querySelectorAll('[data-action]')) {
        button.addEventListener('click', () => vscode.postMessage({ type: button.dataset.action }));
    }

    // Switching the account here switches the status bar too.
    accountSelect.addEventListener('change', () => vscode.postMessage({ type: 'select', id: accountSelect.value }));

    for (const tab of tabs.querySelectorAll('[data-tab]')) {
        tab.addEventListener('click', () => vscode.postMessage({ type: 'tab', tab: tab.dataset.tab }));
    }

    window.addEventListener('message', event => {
        if (event.data && event.data.type === 'model') {
            model = event.data.model;
            hovered = -1;
            hoveredMonth = null;
            render();
        }
    });

    let width = 0;
    new ResizeObserver(() => {
        if (model && document.body.clientWidth !== width) {
            width = document.body.clientWidth;
            drawMeter();
            drawChart();
            drawMonths();
        }
    }).observe(document.body);

    vscode.postMessage({ type: 'ready' });

    function el(tag, className, text) {
        const e = document.createElement(tag);
        if (className) e.className = className;
        if (text != null) e.textContent = text;
        return e;
    }

    function svg(tag, attrs, className) {
        const e = document.createElementNS(SVG, tag);
        for (const key in attrs) e.setAttribute(key, String(attrs[key]));
        if (className) e.setAttribute('class', className);
        return e;
    }

    function text(x, y, content, className, anchor) {
        const t = svg('text', { x, y, 'text-anchor': anchor || 'start' }, className);
        t.textContent = content;
        return t;
    }

    function link(label, action) {
        const b = el('button', 'link', label);
        b.addEventListener('click', () => vscode.postMessage({ type: action }));
        return b;
    }

    const canvas = document.createElement('canvas').getContext('2d');

    /** The width of a label as the SVG will draw it. */
    function measure(content, size, bold) {
        const family = getComputedStyle(document.body).fontFamily;
        canvas.font = (bold ? '700 ' : '') + size + 'px ' + family;
        return canvas.measureText(content).width;
    }

    /** The account picker, most credits left first; hidden until there is an account. */
    function renderAccounts() {
        const accounts = model.accounts || [];
        accountRow.hidden = accounts.length === 0;
        accountSelect.replaceChildren(...accounts.map(a => {
            const option = el('option', null, a.label);
            option.value = a.id;
            option.selected = a.selected;
            return option;
        }));
    }

    /** Why the account has no numbers, and the button that fixes it. */
    function renderNotice(n) {
        const box = el('div', 'notice ' + n.tone);
        box.setAttribute('role', n.tone === 'error' ? 'alert' : 'status');
        box.append(el('p', 'notice-title', n.title), el('p', null, n.text));
        const actions = el('div', 'notice-actions');
        const fix = el('button', 'primary', n.actionLabel);
        fix.addEventListener('click', () => vscode.postMessage({ type: n.action }));
        actions.append(fix, link('Accounts…', 'accounts'));
        box.append(actions);
        page.append(box);
    }

    /** Usage | Statistics, once there is an account. */
    function renderTabs() {
        tabs.hidden = (model.accounts || []).length === 0;
        for (const tab of tabs.querySelectorAll('[data-tab]')) {
            const on = tab.dataset.tab === (model.tab || 'usage');
            tab.setAttribute('aria-selected', String(on));
            tab.classList.toggle('on', on);
        }
    }

    function render() {
        byline.textContent = model.byline;
        byline.title = model.byline;
        renderAccounts();
        renderTabs();
        const searching = document.activeElement && document.activeElement.id === 'session-search';
        page.replaceChildren();
        if (model.tab === 'sessions' && !model.notConfigured) {
            renderSessions(model.sessions);
            const search = document.getElementById('session-search');
            if (searching && search) {
                search.focus();
                search.setSelectionRange(search.value.length, search.value.length);
            }
            return;
        }
        if (model.tab === 'statistics' && !model.notConfigured) {
            renderStatistics(model.statistics);
            width = document.body.clientWidth;
            drawMonths();
            return;
        }
        if (model.error && !model.notice) {
            page.append(el('p', 'error', model.error));
        }
        if (model.notice) {
            renderNotice(model.notice);
        } else if (model.notConfigured) {
            page.append(el('p', null, model.notConfigured));
            const actions = el('div', 'actions');
            actions.append(
                link('Add or Replace a Token', 'addToken'),
                link('Create the token in GitLab ↗', 'createToken'),
                link('Open Settings', 'settings'));
            page.append(actions);
        } else if (model.loading) {
            page.append(el('p', 'secondary', 'Loading…'));
        }
        if (model.report) {
            renderReport(model.report);
            width = document.body.clientWidth;
            drawMeter();
            drawChart();
        }
    }

    function renderReport(r) {
        const headline = el('div', 'headline');
        headline.append(el('span', 'big', r.used), el('span', 'caption', r.caption));
        const pill = el('span', r.blocked ? 'pill blocked' : 'pill');
        const icon = svg('svg', { viewBox: '0 0 12 12', 'aria-hidden': 'true' });
        icon.append(svg('circle', { cx: 6, cy: 6, r: 6, fill: 'currentColor' }));
        icon.append(svg('path', {
            d: r.blocked ? 'M3.4 6h5.2' : 'M3.5 6.2 5.3 8l3.3-3.6',
            fill: 'none', 'stroke-width': 1.4,
            'stroke-linecap': 'round', 'stroke-linejoin': 'round',
        }));
        pill.append(icon, document.createTextNode(r.blocked ? 'Blocked' : 'Active'));
        if (r.blockedBy) pill.title = r.blockedBy;
        headline.append(pill);
        page.append(headline, el('p', 'period secondary', r.period));

        if (r.meter) {
            page.append(el('div', 'meter'));
        }

        const tiles = el('div', 'tiles');
        for (const tile of r.tiles) {
            const box = el('div', 'tile');
            box.title = tile.caption;
            const value = el('div', 'value', tile.value);
            if (tile.unit) value.append(el('span', 'unit', tile.unit));
            box.append(el('div', 'caption', tile.caption), value);
            tiles.append(box);
        }
        page.append(tiles);

        const daily = el('section');
        const heading = el('h2', null, 'Daily usage');
        heading.append(el('span', 'caption', r.daily.caption));
        daily.append(heading, el('div', 'chart'));
        page.append(daily);

        if (r.features.length > 0) {
            const section = el('section');
            section.append(el('h2', null, 'By feature'), featureRows(r.features));
            if (r.featuresError) section.append(el('p', 'small secondary', r.featuresError));
            page.append(section);
        }
        page.append(el('p', 'note secondary', r.note));
    }

    /** A bar a feature, against the costliest. */
    function featureRows(features) {
        const rows = el('div', 'features');
        for (const f of features) {
            const name = el('div', 'name', f.title);
            name.title = f.title;
            const track = el('div', 'track');
            if (f.fraction != null && f.fraction > 0) {
                const fill = el('div', 'fill');
                fill.style.width = (f.fraction * 100) + '%';
                track.append(fill);
            }
            rows.append(name, track, el('div', 'value', f.value));
        }
        return rows;
    }

    /** This account | All accounts. */
    function scopeSwitch(scope) {
        const box = el('div', 'switch scope');
        box.setAttribute('role', 'radiogroup');
        box.setAttribute('aria-label', 'Accounts to show');
        for (const [value, label] of [['account', 'This account'], ['all', 'All accounts']]) {
            const b = el('button', value === scope ? 'on' : null, label);
            b.setAttribute('role', 'radio');
            b.setAttribute('aria-checked', String(value === scope));
            b.addEventListener('click', () => vscode.postMessage({ type: 'scope', scope: value }));
            box.append(b);
        }
        return box;
    }

    /** 3 months | 6 months | 12 months, and Reload, which asks again only what can still change. */
    function rangeRow(range) {
        const row = el('div', 'range-row');
        const box = el('div', 'switch range');
        box.setAttribute('role', 'radiogroup');
        box.setAttribute('aria-label', 'Months to show');
        for (const months of [3, 6, 12]) {
            const b = el('button', months === range ? 'on' : null, months + ' months');
            b.setAttribute('role', 'radio');
            b.setAttribute('aria-checked', String(months === range));
            b.addEventListener('click', () => vscode.postMessage({ type: 'range', months }));
            box.append(b);
        }
        const reload = link('Reload', 'refresh');
        reload.title = 'Ask GitLab again for the months that can still change';
        row.append(box, reload);
        return row;
    }

    /** The last months of usage: the summary, a stacked bar a month, the table, the features and the notes. */
    function renderStatistics(st) {
        if (!st) return;
        if ((model.accounts || []).length > 1) {
            page.append(scopeSwitch(st.scope));
        }
        page.append(rangeRow(st.range));
        if (st.useNotice && model.notice) {
            renderNotice(model.notice);
            return;
        }
        for (const error of st.errors) page.append(el('p', 'error', error));
        if (st.empty) page.append(el('p', 'secondary', st.empty));
        if (st.loading) page.append(el('p', 'secondary', 'Loading statistics…'));
        const s = st.stats;
        if (!s) return;
        if (s.accounts) page.append(el('p', 'small secondary', s.accounts));
        page.append(el('p', 'summary', s.summary));

        const chart = el('section');
        const heading = el('h2', null, 'Last ' + st.range + ' months');
        heading.append(el('span', 'caption', 'credits per month'));
        chart.append(heading);
        if (s.legend.length > 0) {
            const legend = el('ul', 'legend');
            legend.setAttribute('aria-label', 'Features');
            for (const item of s.legend) {
                const entry = el('li');
                const swatch = el('span', 'swatch s-' + item.slot);
                swatch.setAttribute('aria-hidden', 'true');
                entry.append(swatch, document.createTextNode(item.label));
                legend.append(entry);
            }
            chart.append(legend);
        }
        const host = el('div', 'months');
        host.setAttribute('role', 'img');
        host.setAttribute('aria-label', 'Credits per month; the table below has the figures');
        chart.append(host);
        page.append(chart);

        const table = el('table', 'months-table');
        const head = el('tr');
        for (const [label, numeric] of [['Month', false], ['Credits', true], ['Top feature', false], ['Not billed', true]]) {
            const th = el('th', numeric ? 'num' : null, label);
            th.scope = 'col';
            head.append(th);
        }
        const thead = el('thead');
        thead.append(head);
        const tbody = el('tbody');
        for (const row of s.rows) {
            const tr = el('tr');
            tr.append(el('td', null, row.month), el('td', 'num', row.credits), el('td', 'feature', row.topFeature),
                el('td', 'num', row.notBilled));
            tbody.append(tr);
        }
        table.append(thead, tbody);
        page.append(table);

        if (s.features.length > 0 || s.featuresError) {
            const section = el('section');
            section.append(el('h2', null, 'By feature, last ' + st.range + ' months'));
            if (s.features.length > 0) section.append(featureRows(s.features));
            if (s.featuresError) section.append(el('p', 'small secondary', s.featuresError));
            page.append(section);
        }
        if (s.notBilled) page.append(el('p', 'small', s.notBilled));
        page.append(el('p', 'note secondary', s.note));
    }

    /** A session number, gid or address: found on Enter, rather than filtered on. */
    function looksLikeId(text) {
        const t = text.trim();
        return /^#?\d+$/.test(t) || /Workflow\/\d+/.test(t) || (t.includes('://') && /\/\d+\/?([?#].*)?$/.test(t));
    }

    /** Shows the rows whose title or project holds what is typed; all of them for an ID or nothing. */
    function filterSessions() {
        const q = sessionsQuery.trim().toLowerCase();
        const byText = q !== '' && !looksLikeId(q);
        let shown = 0;
        for (const row of page.querySelectorAll('.session')) {
            const visible = !byText || row.dataset.search.includes(q);
            row.hidden = !visible;
            const card = row.nextElementSibling;
            if (card && card.classList.contains('session-details')) card.hidden = !visible;
            if (visible) shown++;
        }
        const none = page.querySelector('.no-match');
        if (none) none.hidden = shown > 0 || !byText;
    }

    /** One session in full: where and how it ran, its steps here, and how its credits were estimated, day by day. */
    function sessionCard(d) {
        const card = el('div', 'session-details');
        card.append(el('p', 'notice-title', d.title));
        const list = el('dl');
        for (const line of d.lines) {
            list.append(el('dt', null, line.label), el('dd', null, line.value));
        }
        card.append(list);
        if (d.shares.length > 0) {
            const shares = el('ul', 'shares');
            for (const share of d.shares) shares.append(el('li', null, share));
            card.append(shares);
        } else {
            card.append(el('p', 'small secondary', 'No credits counted for it in these days.'));
        }
        if (d.url) {
            const open = el('button', 'link', 'Open in GitLab ↗');
            open.addEventListener('click', () => vscode.postMessage({ type: 'open', url: d.url }));
            card.append(open);
        }
        return card;
    }

    /** The Duo CLI session in progress: its steps, round trips, model and ≈ credits, updated as its log grows. */
    function liveCard(c) {
        const card = el('div', 'session-details live-session');
        card.setAttribute('role', 'status');
        const top = el('div', 'live-top');
        top.append(el('p', 'notice-title', 'Duo CLI, now'));
        if (c.session != null) {
            const show = el('button', 'link', 'Session ' + c.session);
            show.title = 'Show this session in full';
            show.addEventListener('click', () => vscode.postMessage({ type: 'sessionsFind', text: String(c.session) }));
            top.append(show);
        } else {
            top.append(el('span', 'small secondary', 'A new session'));
        }
        card.append(top, el('p', 'secondary', c.what), el('p', c.priced ? 'live-credits' : 'secondary', c.credits),
            el('p', 'small secondary', c.basis));
        return card;
    }

    /** Agent Platform sessions in a range, with what each cost as far as it can be told; the Duo CLI's now first. */
    function renderSessions(sv) {
        if (model.live) page.append(liveCard(model.live));
        if (!sv) return;
        const kinds = el('div', 'switch kinds');
        kinds.setAttribute('role', 'radiogroup');
        kinds.setAttribute('aria-label', 'Days to show');
        for (const [kind, label] of [['today', 'Today'], ['yesterday', 'Yesterday'], ['7', 'Last 7 days'], ['30', 'Last 30 days'], ['custom', 'Custom']]) {
            const b = el('button', kind === sv.kind ? 'on' : null, label);
            b.setAttribute('role', 'radio');
            b.setAttribute('aria-checked', String(kind === sv.kind));
            b.addEventListener('click', () => vscode.postMessage({ type: 'sessionsRange', kind }));
            kinds.append(b);
        }
        page.append(kinds);
        if (sv.kind === 'custom') {
            const row = el('div', 'custom-range');
            const from = el('input');
            const to = el('input');
            for (const [input, value, label] of [[from, sv.from, 'From'], [to, sv.to, 'To']]) {
                input.type = 'date';
                input.value = value;
                input.setAttribute('aria-label', label);
                input.addEventListener('change', () => {
                    if (/^\d{4}-\d{2}-\d{2}$/.test(from.value) && /^\d{4}-\d{2}-\d{2}$/.test(to.value)) {
                        vscode.postMessage({ type: 'sessionsRange', kind: 'custom', from: from.value, to: to.value });
                    }
                });
            }
            row.append(el('label', null, 'From'), from, el('label', null, 'To'), to);
            page.append(row);
        }
        page.append(el('p', 'small secondary range-label', sv.label + ' · UTC days, as GitLab counts them'));

        const find = el('div', 'session-find');
        const search = el('input');
        search.id = 'session-search';
        search.type = 'search';
        search.placeholder = 'Session ID, gid or URL; other text filters by title or project';
        search.setAttribute('aria-label', 'Find a session');
        search.value = sessionsQuery;
        search.addEventListener('input', () => {
            sessionsQuery = search.value;
            filterSessions();
        });
        search.addEventListener('keydown', event => {
            if (event.key === 'Enter' && looksLikeId(search.value)) {
                vscode.postMessage({ type: 'sessionsFind', text: search.value });
            }
        });
        const last = el('button', 'secondary-button', 'Last session');
        last.addEventListener('click', () => vscode.postMessage({ type: 'lastSession' }));
        find.append(search, last);
        page.append(find);

        if (sv.useNotice && model.notice) {
            renderNotice(model.notice);
            return;
        }
        if (sv.message) page.append(el('p', 'secondary', sv.message));
        for (const error of sv.errors) page.append(el('p', 'error', error));
        if (sv.loading) page.append(el('p', 'secondary', 'Loading sessions…'));
        const m = sv.model;
        if (!m) return;

        const tiles = el('div', 'tiles');
        for (const tile of m.tiles) {
            const box = el('div', 'tile');
            box.title = tile.caption + ': ' + tile.value;
            box.append(el('div', 'caption', tile.caption), el('div', 'value', tile.value));
            tiles.append(box);
        }
        page.append(tiles);
        if (m.leftOut) page.append(el('p', 'small secondary', m.leftOut));

        if (m.byProject.length > 0) {
            const section = el('section');
            section.append(el('h2', null, 'By project'), featureRows(m.byProject));
            page.append(section);
        }

        const section = el('section');
        section.append(el('h2', null, 'Sessions'));
        if (m.rows.length === 0) {
            section.append(el('p', 'secondary', 'No sessions in these days.'));
        }
        const list = el('div', 'sessions');
        for (const r of m.rows) {
            const row = el('div', 'session' + (m.details && m.details.id === r.id ? ' focused' : ''));
            row.dataset.search = r.search;
            row.setAttribute('role', 'button');
            row.tabIndex = 0;
            row.setAttribute('aria-expanded', String(!!(m.details && m.details.id === r.id)));
            const text = el('div', 'text');
            text.append(el('div', 'title', r.title), el('div', 'meta', r.meta));
            const right = el('div', 'right');
            right.append(el('div', 'credits', r.credits));
            if (r.steps) right.append(el('div', 'meta', r.steps));
            row.append(text, right);
            const toggle = () => vscode.postMessage({ type: 'sessionsFocus', id: r.id });
            row.addEventListener('click', toggle);
            row.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    toggle();
                }
            });
            list.append(row);
            if (m.details && m.details.id === r.id) list.append(sessionCard(m.details));
        }
        section.append(list);
        const none = el('p', 'secondary no-match', 'No session matches.');
        none.hidden = true;
        section.append(none);
        page.append(section);
        page.append(el('p', 'note secondary', m.footer));
        filterSessions();
    }

    /** "M x y+h V y+r Q …": a bar segment with its top corners rounded. */
    function roundedTop(x, y, w, h, r) {
        const rr = Math.max(0, Math.min(r, w / 2, h));
        return 'M' + x + ' ' + (y + h) + 'V' + (y + rr) + 'Q' + x + ' ' + y + ' ' + (x + rr) + ' ' + y
            + 'H' + (x + w - rr) + 'Q' + (x + w) + ' ' + y + ' ' + (x + w) + ' ' + (y + rr) + 'V' + (y + h) + 'Z';
    }

    /** A stacked bar a month, oldest left: the top features in their colours, the rest as Other. */
    function drawMonths() {
        const host = page.querySelector('.months');
        const s = model && model.tab === 'statistics' && model.statistics && model.statistics.stats;
        if (!host || !s) return;
        const w = host.clientWidth, h = 196;
        let labelWidth = 0;
        for (const t of s.ticks) labelWidth = Math.max(labelWidth, measure(t.label, 11));
        const p = { left: labelWidth + 8, right: w - 4, top: 10, bottom: h - 34 };
        const n = Math.max(s.bars.length, 1);
        const slot = (p.right - p.left) / n;
        const barWidth = Math.max(4, Math.min(28, slot * 0.62));
        const y = v => p.bottom - v / s.axisMax * (p.bottom - p.top);
        const svgEl = svg('svg', { width: w, height: h });

        for (const t of s.ticks) {
            const ty = y(t.value);
            svgEl.append(svg('line', { x1: p.left, y1: ty, x2: p.right, y2: ty }, t.value === 0 ? 'axis' : 'grid'));
            svgEl.append(text(p.left - 6, ty + 4, t.label, null, 'end'));
        }

        const monthWidth = measure('Mmm', 11);
        const every = slot < monthWidth + 4 ? 2 : 1;
        const tops = [];
        s.bars.forEach((bar, i) => {
            const x = p.left + i * slot + (slot - barWidth) / 2;
            let base = p.bottom;
            const spans = [];
            bar.segments.forEach((seg, j) => {
                const height = seg.value / s.axisMax * (p.bottom - p.top);
                const top = base - height;
                const drawn = height - (j > 0 ? 2 : 0);
                spans.push([top, base]);
                if (drawn > 0.5) {
                    const last = j === bar.segments.length - 1;
                    const dim = hoveredMonth != null && (hoveredMonth.segment < 0 ? hoveredMonth.bar !== i
                        : !(hoveredMonth.bar === i && hoveredMonth.segment === j));
                    const cls = 'seg s-' + seg.slot + (dim ? ' dim' : '');
                    svgEl.append(last
                        ? svg('path', { d: roundedTop(x, top, barWidth, drawn, 4) }, cls)
                        : svg('rect', { x, y: top, width: barWidth, height: drawn }, cls));
                }
                base = top;
            });
            tops.push({ top: base, spans });
            if (i % every === 0 || bar.year) {
                svgEl.append(text(p.left + (i + 0.5) * slot, p.bottom + 15, bar.label, null, 'middle'));
            }
            if (bar.year) {
                svgEl.append(text(p.left + (i + 0.5) * slot, p.bottom + 28, bar.year, 'year', 'middle'));
            }
        });

        svgEl.addEventListener('mousemove', event => {
            const bounds = svgEl.getBoundingClientRect();
            const mx = event.clientX - bounds.left, my = event.clientY - bounds.top;
            const i = mx < p.left || mx >= p.right ? -1 : Math.floor((mx - p.left) / slot);
            if (i < 0 || i >= s.bars.length || my > p.bottom || my < p.top - 4) {
                return hoverMonth(null);
            }
            const segment = tops[i].spans.findIndex(([top, base]) => my >= top && my <= base);
            hoverMonth({ bar: i, segment });
        });
        svgEl.addEventListener('mouseleave', () => hoverMonth(null));
        host.replaceChildren(svgEl);

        if (hoveredMonth) {
            const bar = s.bars[hoveredMonth.bar];
            const seg = bar.segments[hoveredMonth.segment];
            const tip = el('div', 'months-tip');
            tip.append(el('div', null, bar.title + (seg ? ' · ' + seg.label + ': ' + seg.text : '')),
                el('div', 'secondary', 'Month total: ' + bar.totalText));
            host.append(tip);
            const centre = p.left + (hoveredMonth.bar + 0.5) * slot;
            const left = Math.max(0, Math.min(w - tip.offsetWidth, centre - tip.offsetWidth / 2));
            tip.style.left = left + 'px';
            tip.style.top = Math.max(0, tops[hoveredMonth.bar].top - tip.offsetHeight - 8) + 'px';
        }
    }

    function hoverMonth(next) {
        const same = next === hoveredMonth
            || (next && hoveredMonth && next.bar === hoveredMonth.bar && next.segment === hoveredMonth.segment);
        if (!same) {
            hoveredMonth = next;
            drawMonths();
        }
    }

    /** The left edge of a label of textWidth centred on x, kept inside the width. */
    function centred(x, textWidth, w) {
        return Math.max(0, Math.min(w - textWidth, x - textWidth / 2));
    }

    /** "limit 300" over a bar of what is used, a dashed forecast marker, and ticks at the alert percents. */
    function drawMeter() {
        const host = page.querySelector('.meter');
        const m = model.report && model.report.meter;
        if (!host || !m) return;
        const w = host.clientWidth;
        const s = svg('svg', { width: w, height: 50 });
        const barY = 20, barH = 10, tick = 2;
        s.append(text(w, 11, m.limitText, null, 'end'));

        const clipId = 'meter-clip';
        const clip = svg('clipPath', { id: clipId });
        clip.append(svg('rect', { x: 0, y: barY, width: w, height: barH, rx: barH / 2 }));
        const defs = svg('defs');
        defs.append(clip);
        s.append(defs);
        s.append(svg('rect', { x: 0, y: barY, width: w, height: barH, rx: barH / 2 }, 'track'));
        if (m.used > 0) {
            s.append(svg('rect', { x: 0, y: barY, width: w * m.used, height: barH, 'clip-path': 'url(#' + clipId + ')' }, m.tone));
        }
        for (const percent of m.percents) {
            if (percent < 100) {
                s.append(svg('rect', { x: Math.round(w * percent / 100) - tick / 2, y: barY, width: tick, height: barH }, 'gap'));
            }
        }
        const markerX = Math.min(w * m.forecast, w - tick / 2);
        s.append(svg('line', { x1: markerX, y1: barY - 4, x2: markerX, y2: barY + barH + 4 }, m.forecastOver ? 'marker over' : 'marker'));

        const baseline = barY + barH + 6 + 10;
        const gap = 6;
        const forecastWidth = measure(m.forecastText, 11);
        const forecastX = centred(markerX, forecastWidth, w);
        s.append(text(forecastX, baseline, m.forecastText, m.forecastOver ? 'over' : null));
        const taken = [[forecastX - gap, forecastX + forecastWidth + gap]];
        for (const percent of m.percents) {
            const label = percent + '%';
            const labelWidth = measure(label, 11);
            const x = centred(w * percent / 100, labelWidth, w);
            const span = [x - gap, x + labelWidth + gap];
            if (taken.every(t => !(span[0] < t[1] && t[0] < span[1]))) {
                s.append(text(x, baseline, label));
                taken.push(span);
            }
        }
        host.replaceChildren(s);
    }

    /** One bar a day for the whole period: the days to come shaded behind a "today" line, the hovered day called out. */
    function drawChart() {
        const host = page.querySelector('.chart');
        const d = model.report && model.report.daily;
        if (!host || !d) return;
        const w = host.clientWidth, h = 172;
        let labelWidth = 0;
        for (const t of d.ticks) labelWidth = Math.max(labelWidth, measure(t.label, 11));
        const p = { left: labelWidth + 8, right: w - 8, top: 16, bottom: h - 26 };
        const slot = (p.right - p.left) / d.days;
        const y = v => p.bottom - v / d.axisMax * (p.bottom - p.top);
        const s = svg('svg', { width: w, height: h });
        const showToday = d.todayIndex >= 0 && d.todayIndex < d.days - 1;
        const todayX = p.left + (d.todayIndex + 1) * slot;

        if (showToday) {
            s.append(svg('rect', { x: todayX, y: p.top, width: p.right - todayX, height: p.bottom - p.top }, 'shade'));
            const rest = 'rest of month';
            const restWidth = measure(rest, 11);
            if (p.right - todayX > restWidth + 8) {
                s.append(text((todayX + p.right - restWidth) / 2, (p.top + p.bottom) / 2 + 4, rest));
            }
        }
        for (const t of d.ticks) {
            const ty = y(t.value);
            s.append(svg('line', { x1: p.left, y1: ty, x2: p.right, y2: ty }, t.value === 0 ? 'axis' : 'grid'));
            s.append(text(p.left - 6, ty + 4, t.label, null, 'end'));
        }

        const barWidth = Math.max(1, Math.min(8, slot - 3));
        const radius = Math.min(3, barWidth / 2);
        d.values.forEach((value, i) => {
            if (value <= 0) return;
            const height = Math.max(p.bottom - y(value), 2);
            const x = p.left + i * slot + (slot - barWidth) / 2;
            const top = p.bottom - height;
            const className = i === hovered ? 'bar hovered' : 'bar';
            if (height > radius * 2) {
                // Rounded at the top only: a rounded bar with a square one over its lower part.
                s.append(svg('rect', { x, y: top, width: barWidth, height: radius * 2, rx: radius }, className));
                s.append(svg('rect', { x, y: top + radius, width: barWidth, height: height - radius }, className));
            } else {
                s.append(svg('rect', { x, y: top, width: barWidth, height }, className));
            }
        });

        if (showToday) {
            s.append(svg('line', { x1: todayX, y1: p.top - 4, x2: todayX, y2: p.bottom }, 'today-line'));
            const todayWidth = measure('today', 10);
            s.append(text(Math.min(todayX - todayWidth / 2, w - todayWidth), p.top - 6, 'today', 'today'));
        }
        for (const label of d.xLabels) {
            s.append(text(p.left + (label.index + 0.5) * slot, p.bottom + 17, label.label, null, 'middle'));
        }
        if (hovered >= 0) {
            callout(s, d, p, slot, barWidth, w);
        }

        s.addEventListener('mousemove', event => {
            const bounds = s.getBoundingClientRect();
            const x = event.clientX - bounds.left;
            const index = x < p.left || x >= p.right ? -1 : Math.floor((x - p.left) / slot);
            hover(index <= d.lastShownDay ? index : -1);
        });
        s.addEventListener('mouseleave', () => hover(-1));
        host.replaceChildren(s);
    }

    function hover(index) {
        if (index !== hovered) {
            hovered = index;
            drawChart();
        }
    }

    function callout(s, d, p, slot, barWidth, w) {
        const date = d.dates[hovered];
        const amount = d.amounts[hovered];
        const pad = 10;
        const boxWidth = Math.max(measure(date, 11), measure(amount, 12, true)) + 2 * pad;
        const boxHeight = 38;
        const centre = p.left + (hovered + 0.5) * slot;
        let x = centre + barWidth / 2 + 6;
        if (x + boxWidth > w) {
            x = centre - barWidth / 2 - 6 - boxWidth;
        }
        x = Math.max(0, x);
        const top = p.top + 14;
        s.append(svg('rect', { x: x + 0.5, y: top + 0.5, width: boxWidth, height: boxHeight, rx: 6 }, 'callout'));
        s.append(text(x + pad, top + 16, date, 'callout-date'));
        s.append(text(x + pad, top + 31, amount, 'callout-amount'));
    }
})();
