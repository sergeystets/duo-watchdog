// Lays out the model the extension posts (src/core/dashboard.ts) and draws the meter and the daily chart to the
// view's width, as the IntelliJ plugin's UsageMeter and DailyChart paint them.
(function () {
    const vscode = acquireVsCodeApi();
    const SVG = 'http://www.w3.org/2000/svg';
    const page = document.getElementById('page');
    const byline = document.getElementById('byline');
    let model = null;
    let hovered = -1;

    for (const button of document.querySelectorAll('[data-action]')) {
        button.addEventListener('click', () => vscode.postMessage({ type: button.dataset.action }));
    }

    window.addEventListener('message', event => {
        if (event.data && event.data.type === 'model') {
            model = event.data.model;
            hovered = -1;
            render();
        }
    });

    let width = 0;
    new ResizeObserver(() => {
        if (model && model.report && document.body.clientWidth !== width) {
            width = document.body.clientWidth;
            drawMeter();
            drawChart();
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

    function render() {
        byline.textContent = model.byline;
        byline.title = model.byline;
        page.replaceChildren();
        if (model.error) {
            page.append(el('p', 'error', model.error));
        }
        if (model.notConfigured) {
            page.append(el('p', null, model.notConfigured));
            const actions = el('div', 'actions');
            actions.append(
                link('Set GitLab Token', 'setToken'),
                link('Create the token in GitLab ↗', 'createToken'),
                link('Find My Group', 'findGroup'),
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
            section.append(el('h2', null, 'By feature'));
            const rows = el('div', 'features');
            for (const f of r.features) {
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
            section.append(rows);
            if (r.featuresError) section.append(el('p', 'small secondary', r.featuresError));
            page.append(section);
        }
        page.append(el('p', 'note secondary', r.note));
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
