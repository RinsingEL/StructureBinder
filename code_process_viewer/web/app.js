const state = {
  sessions: [],
  currentSessionId: "",
  sessionView: null,
  graphView: null,
  currentNodeId: "",
  nodeView: null,
  tables: [],
};

const elements = {
  flash: document.getElementById("flash"),
  importForm: document.getElementById("importForm"),
  artifactPath: document.getElementById("artifactPath"),
  replaceExisting: document.getElementById("replaceExisting"),
  sessionSelect: document.getElementById("sessionSelect"),
  reportLink: document.getElementById("reportLink"),
  sessionMeta: document.getElementById("sessionMeta"),
  stepCount: document.getElementById("stepCount"),
  stepList: document.getElementById("stepList"),
  previewState: document.getElementById("previewState"),
  previewBox: document.getElementById("previewBox"),
  detailState: document.getElementById("detailState"),
  detailBox: document.getElementById("detailBox"),
  tablesBox: document.getElementById("tablesBox"),
  checkpointBox: document.getElementById("checkpointBox"),
  reviewForm: document.getElementById("reviewForm"),
  reviewState: document.getElementById("reviewState"),
  reviewComment: document.getElementById("reviewComment"),
  saveReviewButton: document.getElementById("saveReviewButton"),
};

document.addEventListener("DOMContentLoaded", () => {
  bindEvents();
  loadSessions();
});

function bindEvents() {
  elements.importForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const payload = {
      artifact_path: elements.artifactPath.value.trim(),
      replace_existing: elements.replaceExisting.checked,
    };
    if (!payload.artifact_path) {
      showFlash("请先填写调试目录。", "error");
      return;
    }
    try {
      setImportBusy(true);
      const result = await requestJSON("/api/import/jigsaw-debug", {
        method: "POST",
        body: JSON.stringify(payload),
      });
      showFlash(`导入成功：${result.session.title}`, "success");
      elements.artifactPath.value = "";
      await loadSessions(result.session.session_id);
    } catch (error) {
      showFlash(error.message, "error");
    } finally {
      setImportBusy(false);
    }
  });

  elements.sessionSelect.addEventListener("change", async (event) => {
    const next = event.target.value;
    if (next) {
      await loadSession(next);
    }
  });

  elements.reviewForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state.currentNodeId) {
      return;
    }
    try {
      setReviewBusy(true);
      await requestJSON(`/api/nodes/${encodeURIComponent(state.currentNodeId)}/review`, {
        method: "POST",
        body: JSON.stringify({
          review_state: elements.reviewState.value,
          comment: elements.reviewComment.value.trim(),
        }),
      });
      showFlash("审查结论已保存。", "success");
      await loadSession(state.currentSessionId, state.currentNodeId);
    } catch (error) {
      showFlash(error.message, "error");
    } finally {
      setReviewBusy(false);
    }
  });
}

async function loadSessions(preferredSessionId = "") {
  try {
    state.sessions = await requestJSON("/api/sessions");
    renderSessionOptions();
    if (state.sessions.length === 0) {
      resetWorkspace("先导入一个 Jigsaw 调试目录。");
      return;
    }
    const nextSessionId =
      preferredSessionId ||
      state.currentSessionId ||
      state.sessions[0].session_id;
    await loadSession(nextSessionId);
  } catch (error) {
    showFlash(error.message, "error");
    resetWorkspace("加载会话列表失败。");
  }
}

async function loadSession(sessionId, preferredNodeId = "") {
  try {
    const [sessionView, graphView] = await Promise.all([
      requestJSON(`/api/sessions/${encodeURIComponent(sessionId)}`),
      requestJSON(`/api/sessions/${encodeURIComponent(sessionId)}/graph`),
    ]);
    state.currentSessionId = sessionId;
    state.sessionView = sessionView;
    state.graphView = graphView;
    renderSessionOptions();
    renderMeta();
    renderCheckpoints();
    renderSteps();

    const nextNodeId =
      preferredNodeId && graphView.nodes.some((item) => item.node_id === preferredNodeId)
        ? preferredNodeId
        : graphView.nodes[0]?.node_id || "";

    if (nextNodeId) {
      await loadNode(nextNodeId);
    } else {
      clearNodePanels("当前会话没有步骤。");
    }
  } catch (error) {
    showFlash(error.message, "error");
  }
}

async function loadNode(nodeId) {
  try {
    const [nodeView, tables] = await Promise.all([
      requestJSON(`/api/nodes/${encodeURIComponent(nodeId)}`),
      requestJSON(`/api/nodes/${encodeURIComponent(nodeId)}/tables`),
    ]);
    state.currentNodeId = nodeId;
    state.nodeView = nodeView;
    state.tables = tables;
    renderSteps();
    renderPreview();
    renderDetail();
    renderTables();
    syncReviewForm();
  } catch (error) {
    showFlash(error.message, "error");
  }
}

function renderSessionOptions() {
  const sessions = state.sessions;
  if (sessions.length === 0) {
    elements.sessionSelect.innerHTML = `<option value="">暂无会话</option>`;
    elements.reportLink.classList.add("disabled");
    return;
  }
  elements.sessionSelect.innerHTML = sessions
    .map(
      (item) => `
        <option value="${escapeAttr(item.session_id)}" ${item.session_id === state.currentSessionId ? "selected" : ""}>
          ${escapeHTML(item.title)}
        </option>`
    )
    .join("");
}

function renderMeta() {
  const view = state.sessionView;
  if (!view) {
    elements.sessionMeta.innerHTML = `<span class="meta-pill">等待导入</span>`;
    elements.reportLink.classList.add("disabled");
    elements.reportLink.href = "#";
    return;
  }
  const summary = view.import.import_summary || {};
  elements.sessionMeta.innerHTML = `
    <span class="meta-pill">状态：${formatStatus(view.session.status)}</span>
    <span class="meta-pill">runId：${escapeHTML(view.session.debug_run_id)}</span>
    <span class="meta-pill">城市 / 组：${escapeHTML(view.session.city_id || "-")} / ${escapeHTML(view.session.group_id || "-")}</span>
    <span class="meta-pill">步骤：${state.graphView?.nodes?.length || 0}</span>
    <span class="meta-pill">缺失预览图：${summary.missing_preview_count ?? 0}</span>
  `;
  elements.reportLink.classList.remove("disabled");
  elements.reportLink.href = `/api/sessions/${encodeURIComponent(view.session.session_id)}/report`;
}

function renderSteps() {
  const nodes = state.graphView?.nodes || [];
  elements.stepCount.textContent = `${nodes.length} 个步骤`;
  if (nodes.length === 0) {
    elements.stepList.className = "step-list empty-state";
    elements.stepList.textContent = "当前会话没有可展示的步骤。";
    return;
  }

  elements.stepList.className = "step-list";
  elements.stepList.innerHTML = nodes
    .map((item) => {
      const active = item.node_id === state.currentNodeId ? "active" : "";
      return `
        <article class="step-card ${active}" data-node-id="${escapeAttr(item.node_id)}">
          <span class="step-order ${statusClass(item.status)}">${String(item.step_index).padStart(2, "0")}</span>
          <div class="step-title">${escapeHTML(item.name)}</div>
          <div class="secondary">${renderStatusPill(item.status)} ${renderReviewPill(item.review_state)}</div>
          <div class="step-key">${escapeHTML(item.step_key)}</div>
          <div class="step-summary">${escapeHTML(item.summary || "当前步骤暂无摘要。")}</div>
        </article>`;
    })
    .join("");

  elements.stepList.querySelectorAll(".step-card").forEach((card) => {
    card.addEventListener("click", () => loadNode(card.dataset.nodeId));
  });
}

function renderPreview() {
  const nodeView = state.nodeView;
  if (!nodeView) {
    elements.previewState.textContent = "未选择步骤";
    elements.previewBox.className = "preview-box empty-state";
    elements.previewBox.textContent = "当前步骤没有预览图。";
    return;
  }
  const notes = nodeView.detail.decision_notes || [];
  elements.previewState.textContent = `${nodeView.node.name} / ${formatStatus(nodeView.node.status)}`;
  if (nodeView.detail.preview_image) {
    elements.previewBox.className = "preview-box";
    elements.previewBox.innerHTML = `
      <img src="${escapeAttr(nodeView.detail.preview_image)}" alt="${escapeAttr(nodeView.node.name)}">
      ${notes.length ? `<div class="note-box">${notes.map(escapeHTML).join("<br>")}</div>` : ""}`;
    return;
  }
  elements.previewBox.className = "preview-box empty-state";
  elements.previewBox.innerHTML = `
    <div>
      <p>当前步骤没有可展示的预览图。</p>
      ${notes.length ? `<div class="note-box">${notes.map(escapeHTML).join("<br>")}</div>` : ""}
    </div>`;
}

function renderDetail() {
  const nodeView = state.nodeView;
  if (!nodeView) {
    elements.detailState.textContent = "未选择步骤";
    elements.detailBox.className = "detail-box empty-state";
    elements.detailBox.textContent = "点击左侧步骤查看详情。";
    return;
  }
  elements.detailState.textContent = `${nodeView.node.step_key} / 风险 ${nodeView.node.risk_level || "-"}`;
  const sources = nodeView.detail.source_refs || [];
  elements.detailBox.className = "detail-box";
  elements.detailBox.innerHTML = `
    ${renderDetailSection("步骤目标", nodeView.detail.goal_zh)}
    ${renderDetailSection("动作摘要", nodeView.detail.action_summary_zh)}
    ${renderDetailSection("实现说明", nodeView.detail.implementation_zh)}
    ${renderDetailSection("结果摘要", nodeView.detail.result_zh || nodeView.node.summary)}
    <section class="detail-section">
      <h3>源码入口</h3>
      ${sources.length ? sources.map(renderSourceItem).join("") : '<p class="secondary">当前步骤没有固定源码入口。</p>'}
    </section>
  `;
}

function renderTables() {
  if (!state.currentNodeId) {
    elements.tablesBox.className = "tables-box empty-state";
    elements.tablesBox.textContent = "当前步骤还没有结构化表格。";
    return;
  }
  if (!state.tables || state.tables.length === 0) {
    elements.tablesBox.className = "tables-box empty-state";
    elements.tablesBox.textContent = "当前步骤没有 evidence / legend 表格。";
    return;
  }
  elements.tablesBox.className = "tables-box";
  elements.tablesBox.innerHTML = state.tables.map(renderTableCard).join("");
}

function renderCheckpoints() {
  const checkpoints = state.sessionView?.checkpoints || [];
  if (checkpoints.length === 0) {
    elements.checkpointBox.className = "checkpoint-box empty-state";
    elements.checkpointBox.textContent = "当前会话没有检查点。";
    return;
  }
  elements.checkpointBox.className = "checkpoint-box";
  elements.checkpointBox.innerHTML = checkpoints
    .map((item) => `
      <article class="checkpoint-card">
        <div class="checkpoint-copy">
          <h3>${escapeHTML(item.title)}</h3>
          <p class="secondary">优先级：${escapeHTML(item.priority)} / 目标节点：${escapeHTML((item.target_node_ids || []).join(", "))}</p>
          <p class="detail-copy">${escapeHTML(item.expected_result || "请确认结果是否符合预期。")}</p>
        </div>
        <span class="review-pill">${formatReviewState(item.review_state)}</span>
      </article>`)
    .join("");
}

function syncReviewForm() {
  const review = state.nodeView?.review;
  elements.saveReviewButton.disabled = !state.currentNodeId;
  elements.reviewState.value = review?.review_state || state.nodeView?.node.review_state || "pass";
  elements.reviewComment.value = review?.comment || "";
}

function setImportBusy(isBusy) {
  const button = elements.importForm.querySelector("button");
  button.disabled = isBusy;
  button.textContent = isBusy ? "导入中..." : "导入 Jigsaw 调试产物";
}

function setReviewBusy(isBusy) {
  elements.saveReviewButton.disabled = isBusy || !state.currentNodeId;
  elements.saveReviewButton.textContent = isBusy ? "保存中..." : "保存当前步骤审查";
}

function resetWorkspace(message) {
  state.currentSessionId = "";
  state.sessionView = null;
  state.graphView = null;
  state.currentNodeId = "";
  state.nodeView = null;
  state.tables = [];
  renderSessionOptions();
  renderMeta();
  elements.stepCount.textContent = "0 个步骤";
  elements.stepList.className = "step-list empty-state";
  elements.stepList.textContent = message;
  clearNodePanels(message);
  renderCheckpoints();
}

function clearNodePanels(message) {
  elements.previewState.textContent = "未选择步骤";
  elements.previewBox.className = "preview-box empty-state";
  elements.previewBox.textContent = message;
  elements.detailState.textContent = "未选择步骤";
  elements.detailBox.className = "detail-box empty-state";
  elements.detailBox.textContent = message;
  elements.tablesBox.className = "tables-box empty-state";
  elements.tablesBox.textContent = message;
  elements.saveReviewButton.disabled = true;
  elements.reviewComment.value = "";
}

async function requestJSON(url, options = {}) {
  const response = await fetch(url, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const text = await response.text();
  const payload = text ? tryParseJSON(text) : null;
  if (!response.ok) {
    throw new Error(payload?.error || `请求失败：${response.status}`);
  }
  return payload;
}

function tryParseJSON(text) {
  try {
    return JSON.parse(text);
  } catch (error) {
    return { error: text };
  }
}

function renderDetailSection(title, content) {
  return `
    <section class="detail-section">
      <h3>${escapeHTML(title)}</h3>
      <div class="detail-copy">${escapeHTML(content || "当前字段为空。")}</div>
    </section>`;
}

function renderSourceItem(item) {
  const line = item.line_start ? `#L${item.line_start}` : "";
  return `
    <div class="source-item">
      <div><strong>${escapeHTML(item.symbol || "源码入口")}</strong></div>
      <div class="source-path">${escapeHTML(item.path)}${escapeHTML(line)}</div>
    </div>`;
}

function renderTableCard(table) {
  const headers = (table.columns || [])
    .map((column) => `<th>${escapeHTML(column.label || column.key)}</th>`)
    .join("");
  const rows = (table.rows || [])
    .map((row) => `
      <tr>
        ${(table.columns || []).map((column) => `<td>${escapeHTML(stringify(row[column.key]))}</td>`).join("")}
      </tr>`)
    .join("");
  return `
    <section class="table-card">
      <h3>${escapeHTML(table.title)}</h3>
      <div class="table-scroll">
        <table>
          <thead><tr>${headers}</tr></thead>
          <tbody>${rows || `<tr><td colspan="${table.columns.length || 1}">暂无数据</td></tr>`}</tbody>
        </table>
      </div>
    </section>`;
}

function renderStatusPill(status) {
  return `<span class="status-pill">${formatStatus(status)}</span>`;
}

function renderReviewPill(stateValue) {
  return `<span class="review-pill">${formatReviewState(stateValue)}</span>`;
}

function formatStatus(status) {
  switch ((status || "").toLowerCase()) {
    case "ok":
    case "success":
      return "正常";
    case "warning":
      return "警告";
    case "invalid":
      return "无效";
    case "failed":
    case "error":
      return "失败";
    default:
      return status || "未知";
  }
}

function formatReviewState(stateValue) {
  switch ((stateValue || "").toLowerCase()) {
    case "pass":
      return "通过";
    case "concern":
      return "存疑";
    case "reject":
      return "驳回";
    case "need_evidence":
      return "待补证据";
    case "unchecked":
      return "未检查";
    default:
      return stateValue || "未检查";
  }
}

function statusClass(status) {
  switch ((status || "").toLowerCase()) {
    case "warning":
      return "status-warning";
    case "invalid":
      return "status-invalid";
    case "failed":
    case "error":
      return "status-failed";
    default:
      return "status-ok";
  }
}

function stringify(value) {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function showFlash(message, type) {
  elements.flash.className = `flash ${type}`;
  elements.flash.textContent = message;
}

function escapeHTML(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeAttr(value) {
  return escapeHTML(value);
}
