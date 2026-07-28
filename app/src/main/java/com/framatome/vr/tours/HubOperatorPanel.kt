package com.framatome.vr.tours

/** Self-contained markup, styling, and behavior for the hub operator panel. */
object HubOperatorPanel {
  fun headerButton(): String = """
    <button class="operator-settings-button" id="operatorSettingsButton" type="button" onclick="openOperatorSettings()">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21H9.6v-.09A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3V9.6h.09A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.09A1.7 1.7 0 0 0 15.4 4a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.1.38.32.72.6 1 .3.26.68.4 1.1.4h.09v4h-.09c-.42 0-.8.14-1.1.4-.28.28-.5.62-.6 1Z"/>
      </svg>
      Settings
    </button>
  """.trimIndent()

  fun markup(): String = """
    <div class="operator-scrim" id="operatorScrim" onclick="closeOperatorSettings()" aria-hidden="true"></div>
    <aside class="operator-panel" id="operatorPanel" role="dialog" aria-modal="true" aria-labelledby="operatorTitle" aria-hidden="true" inert>
      <div class="operator-panel-header">
        <div>
          <span class="operator-kicker">Device administration</span>
          <h2 id="operatorTitle">Settings &amp; Support</h2>
          <p>Content delivery, maintenance, and device readiness.</p>
        </div>
        <button class="operator-close" id="operatorClose" type="button" onclick="closeOperatorSettings()" aria-label="Close settings">&times;</button>
      </div>

      <div class="operator-panel-body">
        <div class="operator-readiness" id="operatorReadiness">
          <span class="operator-status-dot"></span>
          <div>
            <strong id="operatorReadinessTitle">Loading device status…</strong>
            <p id="operatorReadinessDetail">Checking content services and storage access.</p>
          </div>
        </div>

        <section class="operator-section">
          <div class="operator-section-heading">
            <div>
              <span>Content maintenance</span>
              <p id="operatorRefreshSummary">Latest import status will appear here.</p>
            </div>
          </div>
          <div class="operator-action-grid">
            <button class="operator-action primary" type="button" onclick="operatorRescan()">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M1 4v6h6M23 20v-6h-6"/><path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4-4.64 4.36A9 9 0 0 1 3.51 15"/></svg>
              <span><strong>Rescan content</strong><small>Check active deliveries and rebuild the library.</small></span>
            </button>
            <button class="operator-action" id="operatorPreviewButton" type="button" onclick="rebuildOperatorPreviews(this)">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="m21 15-5-5L5 21"/></svg>
              <span><strong>Rebuild previews</strong><small>Clear generated thumbnails; source files stay untouched.</small></span>
            </button>
          </div>
          <p class="operator-action-message" id="operatorActionMessage" role="status" aria-live="polite"></p>
        </section>

        <section class="operator-section">
          <div class="operator-section-heading">
            <div>
              <span>Storage access</span>
              <p>Required for ArborXR files and the shared media library.</p>
            </div>
            <span class="operator-pill" id="operatorStoragePill">Checking</span>
          </div>
          <div class="operator-data-grid">
            <div><small>Free space</small><strong id="operatorFreeSpace">—</strong></div>
            <div><small>Delivery folder</small><strong id="operatorDataPath">/sdcard/FramatomeVR/Data/</strong></div>
            <div class="operator-wide"><small>Watched folders</small><strong id="operatorDropRoots">/sdcard/FramatomeVR/Data/</strong></div>
          </div>
          <button class="operator-secondary-button" type="button" onclick="openOperatorStorageSettings(this)">
            Open Android storage settings
          </button>
        </section>

        <section class="operator-section">
          <div class="operator-section-heading">
            <div>
              <span>Tour visibility</span>
              <p>Hidden tours remain installed and can be restored at any time.</p>
            </div>
          </div>
          <div class="operator-tour-list" id="operatorTourList">
            <p class="operator-empty">Loading installed tours…</p>
          </div>
        </section>

        <section class="operator-section">
          <div class="operator-section-heading">
            <div>
              <span>Device status</span>
              <p id="operatorLastScan">Waiting for library status…</p>
            </div>
          </div>
          <div class="operator-data-grid status-grid">
            <div><small>App version</small><strong id="operatorVersion">—</strong></div>
            <div><small>Local server</small><strong id="operatorServer">—</strong></div>
            <div><small>Visible tours</small><strong id="operatorTourCount">—</strong></div>
            <div><small>Collections</small><strong id="operatorCollectionCount">—</strong></div>
            <div><small>Videos</small><strong id="operatorVideoCount">—</strong></div>
            <div><small>Images</small><strong id="operatorImageCount">—</strong></div>
            <div><small>3D models</small><strong id="operatorModelCount">—</strong></div>
            <div><small>Content issues</small><strong id="operatorIssueCount">—</strong></div>
          </div>
        </section>

        <section class="operator-section operator-help">
          <div class="operator-section-heading">
            <div>
              <span>ArborXR delivery</span>
              <p>Recommended production configuration.</p>
            </div>
          </div>
          <ol>
            <li>Deliver ZIP packages without ArborXR extraction.</li>
            <li>Use <code id="operatorDeliveryFolder">/sdcard/FramatomeVR/Data/</code> as the destination.</li>
            <li>Wait for the ArborXR file job to finish, then choose Rescan content.</li>
          </ol>
          <p class="operator-note">The Player accepts 3DVista web ZIPs and media packages containing MP4, MOV, M4V, WebM, JPG, PNG, or WebP files. A <code>manifest.json</code> can define projections, stereo layout, titles, and collections.</p>
        </section>
      </div>
    </aside>
  """.trimIndent()

  fun styles(): String = """
    /* --- Hub-native operator settings --- */
    .operator-settings-button{
      min-height:46px;padding:0 15px;border-radius:14px;border:1px solid var(--glass-border);
      background:var(--glass);color:white;display:flex;align-items:center;gap:8px;
      font-size:13px;font-weight:700;cursor:pointer;backdrop-filter:blur(10px);
    }
    .operator-settings-button:hover{background:rgba(255,255,255,.2)}
    body.operator-open{overflow:hidden}
    .operator-scrim{
      display:none;position:fixed;inset:0;z-index:9300;background:rgba(2,8,20,.66);
      backdrop-filter:blur(5px);opacity:0;transition:opacity .18s ease;
    }
    .operator-scrim.active{display:block;opacity:1}
    .operator-panel{
      position:fixed;z-index:9400;top:0;right:0;width:min(700px,92vw);height:100vh;
      background:linear-gradient(160deg,#10284a 0%,#081e3f 52%,#07172f 100%);
      border-left:1px solid var(--glass-border);box-shadow:-18px 0 60px rgba(0,0,0,.42);
      transform:translateX(102%);visibility:hidden;pointer-events:none;
      transition:transform .22s ease,visibility 0s linear .22s;overflow:hidden;
      display:flex;flex-direction:column;
    }
    .operator-panel.active{
      transform:translateX(0);visibility:visible;pointer-events:auto;
      transition:transform .22s ease,visibility 0s;
    }
    .operator-panel-header{
      flex:none;display:flex;justify-content:space-between;gap:24px;align-items:flex-start;
      padding:28px 30px 24px;border-bottom:1px solid var(--glass-border);
      background:rgba(7,23,47,.82);backdrop-filter:blur(14px);
    }
    .operator-kicker{
      display:block;color:var(--orange);font-size:11px;font-weight:800;letter-spacing:1.7px;
      text-transform:uppercase;margin-bottom:4px;
    }
    .operator-panel-header h2{font-size:26px;line-height:1.2}
    .operator-panel-header p{font-size:13px;color:var(--text-mid);margin-top:5px}
    .operator-close{
      width:44px;height:44px;flex:none;border-radius:12px;border:1px solid var(--glass-border);
      background:var(--glass);color:white;font-size:28px;line-height:1;cursor:pointer;
    }
    .operator-panel-body{overflow-y:auto;padding:22px 30px 44px;display:grid;gap:18px}
    .operator-readiness,.operator-section{
      border:1px solid var(--glass-border);border-radius:16px;background:rgba(255,255,255,.075);
    }
    .operator-readiness{display:flex;align-items:center;gap:13px;padding:15px 17px}
    .operator-status-dot{
      width:12px;height:12px;flex:none;border-radius:50%;background:#b9c7d9;
      box-shadow:0 0 0 5px rgba(185,199,217,.12);
    }
    .operator-readiness.ready .operator-status-dot{background:#64d99b;box-shadow:0 0 0 5px rgba(100,217,155,.12)}
    .operator-readiness.warning .operator-status-dot{background:var(--orange);box-shadow:0 0 0 5px rgba(240,78,35,.14)}
    .operator-readiness strong{font-size:14px}
    .operator-readiness p{font-size:12px;color:var(--text-mid);margin-top:2px;line-height:1.4}
    .operator-section{padding:18px}
    .operator-section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:14px}
    .operator-section-heading>div>span{font-size:15px;font-weight:750}
    .operator-section-heading p{font-size:12px;color:var(--text-dim);line-height:1.45;margin-top:3px}
    .operator-pill{
      flex:none;border-radius:999px;padding:5px 10px;background:rgba(185,199,217,.12);
      color:var(--steel);font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.5px;
    }
    .operator-pill.good{background:rgba(100,217,155,.14);color:#9aefbd}
    .operator-pill.bad{background:rgba(240,78,35,.16);color:#ffb39e}
    .operator-action-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
    .operator-action{
      min-height:88px;padding:14px;border:1px solid var(--glass-border);border-radius:13px;
      background:rgba(0,0,0,.14);color:white;text-align:left;display:flex;align-items:flex-start;
      gap:11px;cursor:pointer;
    }
    .operator-action.primary{background:linear-gradient(135deg,rgba(240,78,35,.92),rgba(199,61,22,.92));border-color:rgba(255,255,255,.2)}
    .operator-action:hover,.operator-secondary-button:hover{background-color:rgba(255,255,255,.14)}
    .operator-action:disabled,.operator-secondary-button:disabled,.operator-toggle:disabled{opacity:.55;cursor:wait}
    .operator-action svg{flex:none;margin-top:2px}
    .operator-action span{display:flex;flex-direction:column;gap:4px}
    .operator-action strong{font-size:13px}
    .operator-action small{font-size:11px;line-height:1.45;color:var(--text-mid)}
    .operator-action.primary small{color:rgba(255,255,255,.78)}
    .operator-action-message{min-height:16px;font-size:12px;color:#ffb39e;margin-top:10px;line-height:1.4}
    .operator-data-grid{display:grid;grid-template-columns:1fr 1fr;gap:9px}
    .operator-data-grid>div{
      padding:11px 12px;border-radius:11px;background:rgba(0,0,0,.15);min-width:0;
      display:flex;flex-direction:column;gap:3px;
    }
    .operator-data-grid small{font-size:10px;color:var(--text-dim);text-transform:uppercase;letter-spacing:.5px}
    .operator-data-grid strong{font-size:12px;color:white;overflow-wrap:anywhere}
    .operator-data-grid .operator-wide{grid-column:1/-1}
    .operator-secondary-button{
      min-height:44px;margin-top:11px;width:100%;border-radius:11px;border:1px solid var(--glass-border);
      background:var(--glass);color:white;font-size:12px;font-weight:750;cursor:pointer;
    }
    .operator-tour-list{display:grid;gap:8px}
    .operator-tour-row{
      display:flex;align-items:center;justify-content:space-between;gap:16px;
      padding:11px 12px;border-radius:11px;background:rgba(0,0,0,.15);
    }
    .operator-tour-copy{min-width:0}
    .operator-tour-copy strong{display:block;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .operator-tour-copy small{display:block;font:10px monospace;color:var(--text-dim);margin-top:3px;overflow-wrap:anywhere}
    .operator-toggle{
      flex:none;min-width:76px;min-height:38px;border-radius:999px;border:1px solid var(--glass-border);
      background:rgba(185,199,217,.12);color:var(--steel);font-size:11px;font-weight:800;cursor:pointer;
    }
    .operator-toggle.on{background:rgba(100,217,155,.15);border-color:rgba(100,217,155,.38);color:#9aefbd}
    .operator-empty{font-size:12px;color:var(--text-dim);padding:8px 2px}
    .operator-help ol{padding-left:20px;display:grid;gap:7px;color:var(--text-mid);font-size:12px;line-height:1.5}
    .operator-help code,.operator-note code{
      font-family:monospace;background:rgba(0,0,0,.25);padding:2px 5px;border-radius:5px;color:#ffd9cc;
    }
    .operator-note{margin-top:13px;padding-top:12px;border-top:1px solid var(--glass-border);font-size:11px;line-height:1.55;color:var(--text-dim)}
    @media(max-width:1050px){
      .header{padding-left:28px;padding-right:28px}
      .header-right{flex-wrap:wrap;justify-content:flex-end}
      .main{padding-left:28px;padding-right:28px}
    }
    @media(max-width:700px){
      .operator-panel{width:100vw}
      .operator-panel-header,.operator-panel-body{padding-left:20px;padding-right:20px}
      .operator-action-grid{grid-template-columns:1fr}
    }
  """.trimIndent()

  fun script(): String = """
    var operatorSettingsOpen = false;
    var operatorPreviousFocus = null;
    var operatorStatusTimer = null;
    var operatorToursSignature = null;
    var operatorTourMutationInFlight = false;
    function operatorSetText(id, value) {
      var node = document.getElementById(id);
      if (node) node.textContent = value == null ? '—' : String(value);
    }
    function operatorJson(response) {
      return response.json().then(function(data) {
        if (!response.ok || data.success === false) {
          throw new Error(data.message || ('Operator service returned ' + response.status));
        }
        return data;
      });
    }
    function operatorPost(url) {
      return fetch(url, {
        method:'POST',
        cache:'no-store',
        headers:{'X-Framatome-Request':'hub'}
      }).then(operatorJson);
    }
    function openOperatorSettings(fromHistory) {
      if (operatorSettingsOpen) return;
      dismissIntro();
      operatorSettingsOpen = true;
      operatorPreviousFocus = document.activeElement;
      var panel = document.getElementById('operatorPanel');
      var scrim = document.getElementById('operatorScrim');
      document.body.classList.add('operator-open');
      if (panel) {
        panel.removeAttribute('inert');
        panel.classList.add('active');
        panel.setAttribute('aria-hidden', 'false');
      }
      if (scrim) scrim.classList.add('active');
      if (!fromHistory) {
        try { window.history.pushState({operator:true}, '', '#settings'); } catch (ignored) {}
      }
      var close = document.getElementById('operatorClose');
      if (close) close.focus();
      refreshOperatorStatus();
    }
    function closeOperatorSettings(fromHistory) {
      if (!operatorSettingsOpen) return;
      if (!fromHistory && window.history.state && window.history.state.operator) {
        window.history.back();
        return;
      }
      operatorSettingsOpen = false;
      if (operatorStatusTimer) {
        window.clearTimeout(operatorStatusTimer);
        operatorStatusTimer = null;
      }
      var panel = document.getElementById('operatorPanel');
      var scrim = document.getElementById('operatorScrim');
      document.body.classList.remove('operator-open');
      if (panel) {
        panel.classList.remove('active');
        panel.setAttribute('aria-hidden', 'true');
        panel.setAttribute('inert', '');
      }
      if (scrim) scrim.classList.remove('active');
      var fallback = document.getElementById('operatorSettingsButton');
      var focusTarget = operatorPreviousFocus && operatorPreviousFocus.focus ? operatorPreviousFocus : fallback;
      if (focusTarget && focusTarget.focus) focusTarget.focus();
    }
    function operatorFormatBytes(bytes) {
      var value = Number(bytes) || 0;
      if (value < 1024) return value + ' B';
      var units = ['KB','MB','GB','TB'];
      var unit = -1;
      do { value /= 1024; unit += 1; } while (value >= 1024 && unit < units.length - 1);
      return value.toFixed(value >= 100 ? 0 : 1) + ' ' + units[unit];
    }
    function refreshOperatorStatus() {
      if (!operatorSettingsOpen) return;
      if (operatorStatusTimer) {
        window.clearTimeout(operatorStatusTimer);
        operatorStatusTimer = null;
      }
      fetch('/__operator__/status?ts=' + Date.now(), {cache:'no-store'})
        .then(operatorJson)
        .then(function(status) {
          renderOperatorStatus(status);
          scheduleOperatorStatusRefresh();
        })
        .catch(function(error) {
          var readiness = document.getElementById('operatorReadiness');
          if (readiness) readiness.className = 'operator-readiness warning';
          operatorSetText('operatorReadinessTitle', 'Device status unavailable');
          operatorSetText('operatorReadinessDetail', error.message || 'Try again in a moment.');
          scheduleOperatorStatusRefresh();
        });
    }
    function scheduleOperatorStatusRefresh() {
      if (!operatorSettingsOpen) return;
      operatorStatusTimer = window.setTimeout(refreshOperatorStatus, 2500);
    }
    function renderOperatorStatus(status) {
      var storageReady = !!status.storageGranted;
      var servicesReady = !!status.servicesReady;
      var readiness = document.getElementById('operatorReadiness');
      if (readiness) readiness.className = 'operator-readiness ' + (storageReady && servicesReady ? 'ready' : 'warning');
      operatorSetText(
        'operatorReadinessTitle',
        storageReady && servicesReady ? 'Device ready' : (!storageReady ? 'Storage access required' : 'Content services are starting')
      );
      operatorSetText(
        'operatorReadinessDetail',
        storageReady && servicesReady
          ? 'The Player can import and serve local content.'
          : (!storageReady ? 'Grant All Files Access to read ArborXR deliveries.' : 'Wait a moment, then rescan content.')
      );
      var pill = document.getElementById('operatorStoragePill');
      if (pill) {
        pill.textContent = storageReady ? 'Granted' : 'Required';
        pill.className = 'operator-pill ' + (storageReady ? 'good' : 'bad');
      }
      operatorSetText('operatorFreeSpace', operatorFormatBytes(status.freeBytes));
      operatorSetText('operatorDataPath', status.dataPath);
      operatorSetText('operatorDeliveryFolder', status.dataPath);
      operatorSetText('operatorDropRoots', (status.dropRoots || [status.dataPath]).join(' · '));
      operatorSetText('operatorVersion', status.version);
      operatorSetText('operatorServer', '127.0.0.1:' + status.serverPort);
      var content = status.content || {};
      operatorSetText('operatorTourCount', content.toursCount);
      operatorSetText('operatorCollectionCount', content.collectionsCount);
      operatorSetText('operatorVideoCount', content.videosCount);
      operatorSetText('operatorImageCount', content.imagesCount);
      operatorSetText('operatorModelCount', content.modelsCount);
      operatorSetText('operatorIssueCount', content.issuesCount);
      operatorSetText(
        'operatorLastScan',
        content.scannedAtMs ? 'Library scanned ' + new Date(content.scannedAtMs).toLocaleString() : 'Library has not been scanned yet.'
      );
      var refresh = status.refresh || {};
      var refreshSummary = refresh.message || 'Ready';
      if (refresh.pendingCount) refreshSummary += ' · ' + refresh.pendingCount + ' pending';
      if (refresh.warnings && refresh.warnings.length) refreshSummary += ' · ' + refresh.warnings.length + ' warning(s)';
      operatorSetText('operatorRefreshSummary', refreshSummary);
      renderOperatorTours(status.tours || []);
    }
    function renderOperatorTours(tours) {
      var signature = JSON.stringify(tours.map(function(tour) {
        return [tour.name, tour.relativePath, !!tour.enabled];
      }));
      if (operatorTourMutationInFlight || signature === operatorToursSignature) return;
      operatorToursSignature = signature;
      var list = document.getElementById('operatorTourList');
      if (!list) return;
      while (list.firstChild) list.removeChild(list.firstChild);
      if (!tours.length) {
        var empty = document.createElement('p');
        empty.className = 'operator-empty';
        empty.textContent = 'No installed tours were found.';
        list.appendChild(empty);
        return;
      }
      for (var i = 0; i < tours.length; i++) {
        var tour = tours[i];
        var row = document.createElement('div');
        row.className = 'operator-tour-row';
        var copy = document.createElement('div');
        copy.className = 'operator-tour-copy';
        var title = document.createElement('strong');
        title.textContent = tour.name;
        var path = document.createElement('small');
        path.textContent = tour.relativePath;
        copy.appendChild(title);
        copy.appendChild(path);
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'operator-toggle' + (tour.enabled ? ' on' : '');
        toggle.textContent = tour.enabled ? 'Visible' : 'Hidden';
        toggle.setAttribute('role', 'switch');
        toggle.setAttribute('aria-checked', tour.enabled ? 'true' : 'false');
        toggle.setAttribute('aria-label', (tour.enabled ? 'Hide ' : 'Show ') + tour.name);
        (function(relativePath, nextEnabled, button) {
          button.onclick = function() { setOperatorTourVisibility(relativePath, nextEnabled, button); };
        })(tour.relativePath, !tour.enabled, toggle);
        row.appendChild(copy);
        row.appendChild(toggle);
        list.appendChild(row);
      }
    }
    function setOperatorTourVisibility(path, enabled, button) {
      operatorTourMutationInFlight = true;
      setOperatorTourTogglesDisabled(true);
      operatorPost('/__operator__/tour?path=' + encodeURIComponent(path) + '&enabled=' + (enabled ? 'true' : 'false'))
        .then(function(result) {
          operatorSetText('operatorActionMessage', result.message);
          if (result.changed) {
            try { window.sessionStorage.setItem('framatomeSettingsOpen', '1'); } catch (ignored) {}
            window.setTimeout(function() { window.location.replace('/?settings=' + Date.now()); }, 350);
          } else {
            operatorTourMutationInFlight = false;
            setOperatorTourTogglesDisabled(false);
            refreshOperatorStatus();
          }
        })
        .catch(function(error) {
          operatorTourMutationInFlight = false;
          setOperatorTourTogglesDisabled(false);
          operatorSetText('operatorActionMessage', error.message || 'Tour visibility could not be changed.');
        });
    }
    function setOperatorTourTogglesDisabled(disabled) {
      var toggles = document.querySelectorAll('#operatorTourList .operator-toggle');
      for (var i = 0; i < toggles.length; i++) toggles[i].disabled = disabled;
    }
    function operatorRescan() {
      try { window.sessionStorage.setItem('framatomeSettingsOpen', '1'); } catch (ignored) {}
      refreshContent();
    }
    function rebuildOperatorPreviews(button) {
      button.disabled = true;
      operatorSetText('operatorActionMessage', 'Clearing generated previews…');
      operatorPost('/__operator__/previews?ts=' + Date.now())
        .then(function(result) {
          operatorSetText('operatorActionMessage', result.message + ' Reloading the library…');
          try { window.sessionStorage.setItem('framatomeSettingsOpen', '1'); } catch (ignored) {}
          window.setTimeout(function() { window.location.replace('/?previews=' + Date.now()); }, 450);
        })
        .catch(function(error) {
          button.disabled = false;
          operatorSetText('operatorActionMessage', error.message || 'Previews could not be rebuilt.');
        });
    }
    function openOperatorStorageSettings(button) {
      button.disabled = true;
      operatorSetText('operatorActionMessage', 'Opening Android storage settings…');
      operatorPost('/__operator__/storage?ts=' + Date.now())
        .then(function(result) {
          button.disabled = false;
          operatorSetText('operatorActionMessage', result.message + ' Return here after granting access.');
        })
        .catch(function(error) {
          button.disabled = false;
          operatorSetText('operatorActionMessage', error.message || 'Storage settings could not be opened.');
        });
    }
    document.addEventListener('keydown', function(event) {
      if (event.key === 'Escape' && operatorSettingsOpen) {
        event.stopPropagation();
        closeOperatorSettings();
        return;
      }
      if (event.key === 'Tab' && operatorSettingsOpen) {
        var panel = document.getElementById('operatorPanel');
        var focusable = panel ? panel.querySelectorAll('button:not([disabled])') : [];
        if (!focusable.length) return;
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    });
    window.addEventListener('popstate', function(event) {
      if (event.state && event.state.operator) {
        openOperatorSettings(true);
      } else if (operatorSettingsOpen) {
        closeOperatorSettings(true);
      }
    });
    window.addEventListener('focus', function() {
      if (operatorSettingsOpen) window.setTimeout(refreshOperatorStatus, 250);
    });
    document.addEventListener('visibilitychange', function() {
      if (operatorSettingsOpen && !document.hidden) window.setTimeout(refreshOperatorStatus, 250);
    });
    (function restoreOperatorSettings() {
      try {
        if (window.history.state && window.history.state.operator) {
          dismissIntro();
          window.setTimeout(function() { openOperatorSettings(true); }, 0);
          return;
        }
        if (window.location.hash === '#settings') {
          window.history.replaceState(null, '', window.location.pathname + window.location.search);
          window.history.pushState({operator:true}, '', '#settings');
          dismissIntro();
          window.setTimeout(function() { openOperatorSettings(true); }, 0);
          return;
        }
        if (window.sessionStorage.getItem('framatomeSettingsOpen') === '1') {
          window.sessionStorage.removeItem('framatomeSettingsOpen');
          dismissIntro();
          window.setTimeout(openOperatorSettings, 0);
        }
      } catch (ignored) {}
    })();
  """.trimIndent()
}
