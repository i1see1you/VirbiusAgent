import type { CompletionSource, CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import type { Diagnostic } from '@codemirror/lint';
import type { EditorView } from '@codemirror/view';
import { admin } from '@/api/client';

export interface ScriptCompletionContext {
  runtime: 'groovy' | 'lua' | 'json' | 'prompt';
  listNames: string[];
  cumNames: string[];
  contextVars: string[];
}

const GROOVY_API: { label: string; detail: string; type: string; insert: string }[] = [
  { label: 'def decide(ctx)', detail: 'kw', type: 'keyword', insert: 'def decide(ctx) {\n  \n}' },
  { label: 'ctx.listMatch(name)', detail: 'fn', type: 'function', insert: "ctx.listMatch('')" },
  { label: 'ctx.listMatch(name, value)', detail: 'fn', type: 'function', insert: "ctx.listMatch('', ctx.content)" },
  { label: 'ctx.getCumulative(name)', detail: 'fn', type: 'function', insert: "ctx.getCumulative('')" },
  { label: 'ctx.var(logical)', detail: 'fn', type: 'function', insert: "ctx.var('')" },
  { label: 'ctx.wouldHitBlock()', detail: 'fn', type: 'function', insert: 'ctx.wouldHitBlock()' },
  { label: 'ctx.content', detail: 'field', type: 'variable', insert: 'ctx.content' },
  { label: 'ctx.user_id', detail: 'field', type: 'variable', insert: 'ctx.user_id' },
  { label: 'ctx.device_id', detail: 'field', type: 'variable', insert: 'ctx.device_id' },
  { label: 'ctx.client_ip', detail: 'field', type: 'variable', insert: 'ctx.client_ip' },
  { label: 'ctx.session_id', detail: 'field', type: 'variable', insert: 'ctx.session_id' },
  { label: 'ctx.scene', detail: 'field', type: 'variable', insert: 'ctx.scene' },
  { label: 'ctx.route_uri', detail: 'field', type: 'variable', insert: 'ctx.route_uri' },
  { label: 'return', detail: 'kw', type: 'keyword', insert: 'return ' },
  { label: 'if else', detail: 'kw', type: 'keyword', insert: 'if () {\n  \n} else {\n  \n}' }
];

const LUA_API: { label: string; detail: string; type: string; insert: string }[] = [
  { label: 'function decide(ctx)', detail: 'kw', type: 'keyword', insert: 'function decide(ctx)\n  \nend' },
  { label: 'listMatch(name, value)', detail: 'fn', type: 'function', insert: "listMatch('', ctx.content)" },
  { label: 'getCumulative(name)', detail: 'fn', type: 'function', insert: "getCumulative('')" },
  { label: 'ctx.var(logical)', detail: 'fn', type: 'function', insert: "ctx.var('')" },
  { label: 'ctx.content', detail: 'field', type: 'variable', insert: 'ctx.content' },
  { label: 'ctx.user_id', detail: 'field', type: 'variable', insert: 'ctx.user_id' },
  { label: 'ctx.device_id', detail: 'field', type: 'variable', insert: 'ctx.device_id' },
  { label: 'ctx.client_ip', detail: 'field', type: 'variable', insert: 'ctx.client_ip' },
  { label: 'ctx.session_id', detail: 'field', type: 'variable', insert: 'ctx.session_id' },
  { label: 'ctx.scene', detail: 'field', type: 'variable', insert: 'ctx.scene' },
  { label: 'ctx.route_uri', detail: 'field', type: 'variable', insert: 'ctx.route_uri' },
  { label: 'return', detail: 'kw', type: 'keyword', insert: 'return ' },
  { label: 'if then else end', detail: 'kw', type: 'keyword', insert: 'if  then\n  \nelse\n  \nend' },
  { label: 'end', detail: 'kw', type: 'keyword', insert: 'end' }
];

const BADGE: Record<string, string> = { fn: 'ƒ', field: '·', kw: '⌘', list: '☰', cum: 'Σ', var: 'x' };

function buildApiCompletionSource(ctx: ScriptCompletionContext): CompletionSource {
  const items = ctx.runtime === 'lua' ? LUA_API : (ctx.runtime === 'groovy' ? GROOVY_API : []);
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const word = completionCtx.matchBefore(/[\w.]+/);
    if (!word || (word.from === word.to && !completionCtx.explicit)) return null;
    const filter = word.text.toLowerCase();
    const filtered = filter ? items.filter(it => it.label.toLowerCase().includes(filter)) : items;
    if (!filtered.length) return null;
    return {
      from: word.from,
      options: filtered.map(it => ({
        label: it.label,
        type: it.type,
        detail: it.detail,
        info: it.insert,
        apply: (view: EditorView, _completion: any, from: number, to: number) => {
          let insertText = it.insert;
          let cursorOffset = insertText.length;
          const quoteIdx = insertText.indexOf("''");
          if (quoteIdx >= 0) cursorOffset = quoteIdx + 1;
          view.dispatch({
            changes: { from, to, insert: insertText },
            selection: { anchor: from + cursorOffset }
          });
        }
      }))
    };
  };
}

function buildListCompletionSource(ctx: ScriptCompletionContext): CompletionSource {
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const head = completionCtx.state.doc.sliceString(0, completionCtx.pos);
    const listMatch = head.match(/(?:listMatch|ctx\.listMatch)\s*\(\s*['"]?([^'"]*)$/);
    if (!listMatch) return null;
    const filter = listMatch[1] || '';
    const names = ctx.listNames.filter(n => !filter || n.toLowerCase().includes(filter.toLowerCase()));
    if (!names.length) return null;
    const from = completionCtx.pos - filter.length;
    return {
      from,
      options: names.map(n => ({
        label: n,
        type: 'variable',
        detail: 'list',
        apply: (view: EditorView, _c: any, f: number, t: number) => {
          const before = view.state.doc.sliceString(0, f);
          const quoted = before.endsWith("'") || before.endsWith('"');
          const quote = before.endsWith('"') ? '"' : "'";
          const insertText = quoted ? (n + quote + ')') : ("'" + n + "')");
          view.dispatch({
            changes: { from: f, to: t, insert: insertText },
            selection: { anchor: f + insertText.length }
          });
        }
      }))
    };
  };
}

function buildCumCompletionSource(ctx: ScriptCompletionContext): CompletionSource {
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const head = completionCtx.state.doc.sliceString(0, completionCtx.pos);
    const cumMatch = head.match(/(?:getCumulative|ctx\.getCumulative)\s*\(\s*['"]?([^'"]*)$/);
    if (!cumMatch) return null;
    const filter = cumMatch[1] || '';
    const names = ctx.cumNames.filter(n => !filter || n.toLowerCase().includes(filter.toLowerCase()));
    if (!names.length) return null;
    const from = completionCtx.pos - filter.length;
    return {
      from,
      options: names.map(n => ({
        label: n,
        type: 'variable',
        detail: 'cumulative',
        apply: (view: EditorView, _c: any, f: number, t: number) => {
          const before = view.state.doc.sliceString(0, f);
          const quoted = before.endsWith("'") || before.endsWith('"');
          const quote = before.endsWith('"') ? '"' : "'";
          const insertText = quoted ? (n + quote + ')') : ("'" + n + "')");
          view.dispatch({
            changes: { from: f, to: t, insert: insertText },
            selection: { anchor: f + insertText.length }
          });
        }
      }))
    };
  };
}

function buildVarCompletionSource(ctx: ScriptCompletionContext): CompletionSource {
  return (completionCtx: CompletionContext): CompletionResult | null => {
    const head = completionCtx.state.doc.sliceString(0, completionCtx.pos);
    const varMatch = head.match(/ctx\.var\s*\(\s*['"]?([^'"]*)$/);
    if (!varMatch) return null;
    const filter = varMatch[1] || '';
    const names = ctx.contextVars.filter(n => !filter || n.toLowerCase().includes(filter.toLowerCase()));
    if (!names.length) return null;
    const from = completionCtx.pos - filter.length;
    return {
      from,
      options: names.map(n => ({
        label: n,
        type: 'variable',
        detail: 'context var',
        apply: (view: EditorView, _c: any, f: number, t: number) => {
          const before = view.state.doc.sliceString(0, f);
          const quoted = before.endsWith("'") || before.endsWith('"');
          const quote = before.endsWith('"') ? '"' : "'";
          const insertText = quoted ? (n + quote + ')') : ("'" + n + "')");
          view.dispatch({
            changes: { from: f, to: t, insert: insertText },
            selection: { anchor: f + insertText.length }
          });
        }
      }))
    };
  };
}

export function buildCompletionSources(ctx: ScriptCompletionContext): CompletionSource[] {
  if (ctx.runtime !== 'groovy' && ctx.runtime !== 'lua') return [];
  return [
    buildListCompletionSource(ctx),
    buildCumCompletionSource(ctx),
    buildVarCompletionSource(ctx),
    buildApiCompletionSource(ctx)
  ];
}

export interface LintContext {
  runtime: 'groovy' | 'lua' | 'json' | 'prompt';
  layer: string;
  listNames: string[];
  cumNames: string[];
  contextVars: string[];
}

let lintTimer: ReturnType<typeof setTimeout> | null = null;
let lastLintResult: Diagnostic[] = [];

export function buildLintFn(ctx: LintContext) {
  return (view: EditorView): Diagnostic[] => {
    return lastLintResult;
  };
}

export async function runLint(view: EditorView, ctx: LintContext): Promise<Diagnostic[]> {
  const doc = view.state.doc.toString();
  if (!doc.trim()) { lastLintResult = []; return []; }

  const diagnostics: Diagnostic[] = [];

  if (ctx.runtime === 'groovy' || ctx.runtime === 'lua') {
    try {
      const data = await admin<any>('/rules/validate-script', {
        method: 'POST',
        body: JSON.stringify({ layer: ctx.layer, runtime: ctx.runtime, body: doc })
      });
      if (!data.valid && data.errors) {
        for (const err of data.errors) {
          const lineMatch = err.match(/line (\d+)/i);
          const lineNum = lineMatch ? Number(lineMatch[1]) : 1;
          const line = view.state.doc.line(Math.min(lineNum, view.state.doc.lines));
          diagnostics.push({
            from: line.from,
            to: line.to,
            severity: 'error',
            message: err
          });
        }
      }
      if (data.warnings) {
        for (const warn of data.warnings) {
          const lineMatch = warn.match(/line (\d+)/i);
          const lineNum = lineMatch ? Number(lineMatch[1]) : 1;
          const line = view.state.doc.line(Math.min(lineNum, view.state.doc.lines));
          diagnostics.push({
            from: line.from,
            to: line.to,
            severity: 'warning',
            message: warn
          });
        }
      }
    } catch { /* ignore */ }

    const declared = new Set([
      'rule_id', 'rule_revision', 'tenant_id', 'reason_code', 'intent_action', 'risk_score', 'hit_at',
      'user_id', 'device_id', 'client_ip', 'session_id', 'content', 'scene', 'route_uri',
      'app_id', 'tool_name', 'tool_session_key'
    ]);
    ctx.contextVars.forEach(v => { if (v) declared.add(v); });
    const warned = new Set<string>();
    const re = /ctx\.var\s*\(\s*['"]([^'"]+)['"]\s*\)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(doc)) !== null) {
      const name = m[1];
      if (!declared.has(name) && !warned.has(name)) {
        warned.add(name);
        const pos = m.index;
        diagnostics.push({
          from: pos,
          to: pos + m[0].length,
          severity: 'warning',
          message: `Undeclared variable: ${name}`
        });
      }
    }

    const listRe = /(?:listMatch|ctx\.listMatch)\s*\(\s*['"]([^'"]+)['"]/g;
    while ((m = listRe.exec(doc)) !== null) {
      const name = m[1];
      if (ctx.listNames.length && !ctx.listNames.includes(name)) {
        diagnostics.push({
          from: m.index,
          to: m.index + m[0].length,
          severity: 'warning',
          message: `List not found: ${name}`
        });
      }
    }

    const cumRe = /(?:getCumulative|ctx\.getCumulative)\s*\(\s*['"]([^'"]+)['"]/g;
    while ((m = cumRe.exec(doc)) !== null) {
      const name = m[1];
      if (ctx.cumNames.length && !ctx.cumNames.includes(name)) {
        diagnostics.push({
          from: m.index,
          to: m.index + m[0].length,
          severity: 'warning',
          message: `Cumulative not found: ${name}`
        });
      }
    }
  } else if (ctx.runtime === 'json') {
    try {
      JSON.parse(doc);
    } catch (e: any) {
      const posMatch = e.message.match(/position (\d+)/);
      const pos = posMatch ? Number(posMatch[1]) : 0;
      diagnostics.push({
        from: Math.min(pos, view.state.doc.length),
        to: Math.min(pos + 1, view.state.doc.length),
        severity: 'error',
        message: e.message
      });
    }
  }

  lastLintResult = diagnostics;
  return diagnostics;
}

export function debouncedLint(view: EditorView, ctx: LintContext, callback: (diags: Diagnostic[]) => void) {
  if (lintTimer) clearTimeout(lintTimer);
  lintTimer = setTimeout(async () => {
    const diags = await runLint(view, ctx);
    callback(diags);
  }, 800);
}
