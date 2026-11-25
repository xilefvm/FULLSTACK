import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, WritableSignal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

type Format = 'CSV' | 'JSONL' | 'XLSX';
type State = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
interface Run {
  id: string;
  submittedAt: string;
  state: State;
  format: Format;
  schemaName: string;
  schemaVersion: number;
  totalRows: number | null;
  invalidRows: number | null;
  error: string | null;
  recordsExpiresAt?: string | null;
  parentRunId?: string | null;
}
interface Issue {
  row: number;
  index: number;
  column: string;
  code: string;
  suggestion?: {action: string; candidates: string[]; relatedRow: number | null} | null;
}
interface SchemaVersion {
  name: string;
  version: number;
}
interface Edit {
  row: number;
  column: string;
  value: string;
}
interface Message {
  text: string;
  error: boolean;
}
const labels: Record<State, string> = {
  QUEUED: 'En cola', RUNNING: 'Procesando', COMPLETED: 'Completada', FAILED: 'Fallida',
};
const starterSchema = {
  delimiter: ',',
  columns: [{name: 'id', type: 'INTEGER', required: true}],
};
const emptyMessage: Message = {text: '', error: false};
const uuidPattern = /^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/;
const serverMessages: Record<string, string> = {
  'Dataset file is empty': 'El archivo está vacío.',
  'Run not found': 'No se encontró la ejecución.',
  'Schema version not found': 'No se encontró el esquema o la versión.',
  'Results are available after successful processing': 'Los resultados solo están disponibles tras una validación completada.',
  'This schema version is immutable': 'La versión ya existe con una definición diferente.',
  'Retained rows have expired': 'Las filas conservadas han caducado.',
  'Names must start with a letter and use up to 64 ASCII letters, digits, underscores or hyphens':
    'El nombre debe empezar por una letra y usar hasta 64 letras ASCII, dígitos, guiones bajos o guiones.',
  'Header does not match the schema': 'La cabecera no coincide con el esquema.',
  'Malformed UTF-8 CSV or invalid header': 'El CSV no es UTF-8 válido o su cabecera no es correcta.',
  'Malformed UTF-8 JSONL': 'El JSONL no es UTF-8 válido.',
  'Malformed or unsupported XLSX workbook': 'El libro Excel está dañado o no es compatible.',
  'XLSX has no sheets': 'El libro Excel no contiene hojas.',
  'Requested XLSX sheet does not exist': 'La hoja indicada no existe en el libro.',
  'XLSX must have a header on row 1': 'La fila 1 de la hoja debe contener la cabecera.',
  'XLSX header must contain column names as text': 'Las cabeceras de Excel deben ser texto.',
  'XLSX contains too many ZIP entries': 'El libro Excel contiene demasiadas entradas.',
  'XLSX exceeds 32 MiB after decompression': 'El libro Excel supera 32 MiB descomprimidos.',
  'Validation could not be completed': 'La validación no pudo completarse.',
};
const serverPatterns: [RegExp, string][] = [
  [/^Malformed JSON on line (\d+)$/, 'JSON mal formado en la línea $1.'],
  [/^Expected a JSON object on line (\d+)$/, 'La línea $1 no es un objeto JSON.'],
  [/^XLSX formula on worksheet row (\d+)$/, 'Hay una fórmula en la fila $1 de Excel. Conviértela en valor antes de cargar el archivo.'],
];
function missingSchema(name: string, version: string): string {
  return `No existe la versión ${name} v${version}. Guárdala en Esquemas, elígela en Versiones registradas ` +
    '(el nombre distingue mayúsculas).';
}

/** Maps server messages (English) to Spanish; unknown messages get the fallback. */
function translate(detail: string | null | undefined, fallback: string): string {
  if (!detail) return fallback;
  if (serverMessages[detail]) return serverMessages[detail];
  for (const [pattern, text] of serverPatterns) {
    const match = pattern.exec(detail);
    if (match) return text.replace('$1', match[1]);
  }
  return fallback;
}

@Component({
  selector: 'dataclean-app',
  templateUrl: './dashboard.html',
})
export class Dashboard {
  private readonly http = inject(HttpClient);
  private timer?: ReturnType<typeof setTimeout>;
  private lookupToken = 0;
  private historyToken = 0;

  readonly schemaPattern = String.raw`[A-Za-z][A-Za-z0-9_\-]{0,63}`;
  readonly definition = signal(JSON.stringify(starterSchema, null, 2));
  readonly format = signal<Format>('CSV');
  readonly fileName = signal('');
  readonly uploadName = signal('');
  readonly uploadVersion = signal('1');
  readonly uploadBusy = signal(false);
  readonly schemaBusy = signal(false);
  readonly uploadMessage = signal(emptyMessage);
  readonly schemaMessage = signal(emptyMessage);
  readonly run = signal<Run | null>(null);
  readonly runId = signal('');
  readonly runError = signal('');
  readonly issues = signal<Issue[] | null>(null);
  readonly issueError = signal('');
  readonly edits = signal<Edit[]>([]);
  readonly correctionBusy = signal(false);
  readonly correctionMessage = signal(emptyMessage);
  readonly original = signal<Run | null>(null);
  readonly visual = signal<Record<string, unknown>[] | null>(null);
  readonly history = signal<Run[]>([]);
  readonly historyState = signal('');
  readonly historyBusy = signal(false);
  readonly historyError = signal('');
  readonly stateLabels = labels;
  readonly columnTypes = ['STRING', 'INTEGER', 'DECIMAL', 'DATE', 'BOOLEAN'];
  readonly versions = signal<SchemaVersion[]>([]);
  readonly versionsError = signal('');
  private versionToken = 0;
  private readonly dateFormat = new Intl.DateTimeFormat('es', {dateStyle: 'short', timeStyle: 'short'});
  readonly status = computed(() => {
    const run = this.run();
    if (run) return `${labels[run.state]} · ${run.format} · ${run.schemaName} v${run.schemaVersion}`;
    if (this.runError()) return 'No se pudo consultar la ejecución.';
    return this.runId() ? 'Consultando ejecución…' : 'Selecciona un archivo o busca una ejecución.';
  });
  readonly validRows = computed(() => {
    const run = this.run();
    return run?.totalRows == null || run.invalidRows == null ? null : run.totalRows - run.invalidRows;
  });

  constructor() {
    void this.loadHistory();
    void this.loadVersions();
    const saved = new URLSearchParams(location.search).get('run');
    if (saved && uuidPattern.test(saved)) this.watch(saved);
    inject(DestroyRef).onDestroy(() => {
      ++this.lookupToken;
      ++this.historyToken;
      ++this.versionToken;
      clearTimeout(this.timer);
    });
  }

  selectFile(file: File | null | undefined) {
    this.fileName.set(file?.name ?? '');
    const extension = file?.name.split('.').pop()?.toUpperCase();
    if (extension) this.changeFormat(extension);
  }

  changeFormat(value: string) {
    if (value === 'CSV' || value === 'JSONL' || value === 'XLSX') this.format.set(value);
  }

  metric(value?: number | null) {
    return value?.toLocaleString('es') ?? '—';
  }

  date(value: string) {
    return this.dateFormat.format(new Date(value));
  }

  correction(issue: Issue): string {
    const hint = issue.suggestion;
    if (!hint) return issue.code === 'REQUIRED' ? 'Completa el campo.' : '—';
    const values = hint.candidates.join(' / ');
    switch (hint.action) {
      case 'REPLACE': return 'Propuesta: ' + values;
      case 'REVIEW_UNIQUENESS': return 'Comprueba la unicidad de: ' + values;
      case 'REVIEW_DUPLICATE': return 'Revisa la primera aparición en la fila ' + hint.relatedRow + '.';
      case 'CONFIRM_DATE_FORMAT': return values ? 'Confirma el formato de origen: ' + values : 'Revisa el formato de la fecha.';
      default: return 'Revisión manual.';
    }
  }

  startVisual(value: string) {
    try {
      const parsed = JSON.parse(value);
      if (!Array.isArray(parsed?.columns) || parsed.columns.some((column: unknown) =>
        !column || typeof column !== 'object' || Array.isArray(column))) {
        throw new Error('La definición debe contener una lista de columnas.');
      }
      this.visual.set(parsed.columns);
      this.schemaMessage.set(emptyMessage);
    } catch {
      this.schemaMessage.set({text: 'Corrige la definición JSON antes de abrir el editor visual.', error: true});
    }
  }

  updateColumn(index: number, key: string, value: unknown) {
    this.visual.update(columns => columns?.map((column, position) => {
      if (position !== index) return column;
      const updated = {...column, [key]: value};
      if (key === 'type') {
        if (value !== 'STRING') delete updated['format'];
        if (value !== 'DECIMAL') delete updated['inputDecimalSeparator'];
        if (value !== 'INTEGER' && value !== 'DECIMAL') {
          delete updated['min'];
          delete updated['max'];
        }
      }
      return updated;
    }) ?? null);
  }

  addColumn() {
    this.visual.update(columns => [...(columns ?? []), {name: '', type: 'STRING'}]);
  }

  removeColumn(index: number) {
    this.visual.update(columns => columns?.filter((_, position) => position !== index) ?? null);
  }

  applyVisual(input: HTMLTextAreaElement) {
    const parsed = JSON.parse(input.value);
    const value = JSON.stringify({...parsed, columns: this.visual()}, null, 2);
    input.value = value;
    this.definition.set(value);
    this.visual.set(null);
  }

  field(column: Record<string, unknown>, name: string) {
    return String(column[name] ?? '');
  }

  numericField(index: number, name: string, value: string) {
    this.updateColumn(index, name, value === '' ? null : Number(value));
  }

  canDownloadRows(expiry: string) {
    return Date.parse(expiry) > Date.now();
  }

  stageEdit(issue: Issue, value: string) {
    this.edits.update(items => [...items.filter(item => item.row !== issue.row || item.column !== issue.column),
      {row: issue.row, column: issue.column, value}]);
  }

  removeEdit(edit: Edit) {
    this.edits.update(items => items.filter(item => item !== edit));
  }

  revalidate() {
    const id = this.runId();
    const token = this.lookupToken;
    return this.perform(this.correctionBusy, this.correctionMessage, async () => {
      const result = await firstValueFrom(this.http.post<{id: string}>(
        `/api/runs/${encodeURIComponent(id)}/corrections`, {edits: this.edits()}));
      if (token === this.lookupToken) this.watch(result.id);
      return '';
    });
  }

  async loadVersions(prefix = '') {
    const token = ++this.versionToken;
    this.versionsError.set('');
    try {
      const params = new HttpParams().set('prefix', prefix);
      const versions = await firstValueFrom(this.http.get<SchemaVersion[]>('/api/schemas', {params}));
      if (token === this.versionToken) this.versions.set(versions);
    } catch (error) {
      if (token === this.versionToken) this.versionsError.set(this.describe(error));
    }
  }

  useVersion(value: string) {
    const entry = this.versions().find(version => version.name + ':' + version.version === value);
    if (!entry) return;
    this.uploadName.set(entry.name);
    this.uploadVersion.set(String(entry.version));
  }

  filterHistory(state: string) {
    this.historyState.set(state);
    this.history.set([]);
    void this.loadHistory();
  }

  async loadHistory() {
    const token = ++this.historyToken;
    const state = this.historyState();
    this.historyBusy.set(true);
    this.historyError.set('');
    try {
      const params = state ? new HttpParams().set('state', state) : new HttpParams();
      const result = await firstValueFrom(this.http.get<Run[]>('/api/runs', {params}));
      if (token === this.historyToken) this.history.set(result);
    } catch (error) {
      if (token === this.historyToken) this.historyError.set(this.describe(error));
    } finally {
      if (token === this.historyToken) this.historyBusy.set(false);
    }
  }

  openRun(id: string) {
    this.watch(id);
  }

  save(event: Event, name: string, version: string, value: string) {
    event.preventDefault();
    return this.perform(this.schemaBusy, this.schemaMessage, async () => {
      let body: unknown;
      try {
        body = JSON.parse(value);
      } catch {
        throw new Error('La definición debe ser JSON válido.');
      }
      await firstValueFrom(this.http.put(this.schemaPath(name, version), body));
      void this.loadVersions();
      this.uploadName.set(name);
      this.uploadVersion.set(version);
      return 'Versión disponible para validar archivos.';
    });
  }

  load(name: HTMLInputElement, version: HTMLInputElement) {
    if (!name.reportValidity() || !version.reportValidity()) return;
    return this.perform(this.schemaBusy, this.schemaMessage, async () => {
      const data = await firstValueFrom(this.http.get(this.schemaPath(name.value, version.value))).catch((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 404) throw new Error(missingSchema(name.value, version.value));
        throw error;
      });
      this.definition.set(JSON.stringify(data, null, 2));
      return 'Versión cargada.';
    });
  }

  submit(event: Event, form: HTMLFormElement) {
    event.preventDefault();
    return this.perform(this.uploadBusy, this.uploadMessage, () => this.send(new FormData(form)));
  }

  lookup(event: Event, id: string) {
    event.preventDefault();
    this.watch(id.trim());
  }

  private async send(data: FormData) {
    const file = data.get('file');
    if (!(file instanceof File) || !file.size) throw new Error('Selecciona un archivo con contenido.');
    if (file.size > 20 * 1024 * 1024) throw new Error('El archivo supera los 20 MiB.');
    const sheet = data.get('sheet');
    if (typeof sheet !== 'string' || !sheet.trim()) data.delete('sheet');
    const name = String(data.get('schemaName') ?? '');
    const version = String(data.get('schemaVersion') ?? '');
    const {id} = await firstValueFrom(this.http.post<{id: string}>('/api/runs', data)).catch((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 404) throw new Error(missingSchema(name, version));
      throw error;
    });
    this.watch(id);
    void this.loadHistory();
    return 'Archivo aceptado. Consulta su estado en Resultados.';
  }

  private watch(id: string) {
    const token = ++this.lookupToken;
    clearTimeout(this.timer);
    this.runId.set(id);
    // Keep the run in the URL so a page reload reopens the same results.
    history.replaceState(history.state, '', `?run=${encodeURIComponent(id)}${location.hash}`);
    this.run.set(null);
    this.runError.set('');
    this.issues.set(null);
    this.issueError.set('');
    this.edits.set([]);
    this.original.set(null);
    this.correctionMessage.set(emptyMessage);
    void this.refresh(id, token);
  }

  private async refresh(id: string, token: number) {
    try {
      const run = await firstValueFrom(this.http.get<Run>(`/api/runs/${encodeURIComponent(id)}`));
      if (token !== this.lookupToken) return;
      this.run.set(run);
      if (run.parentRunId && !this.original()) void this.loadOriginal(run.parentRunId, token);
      if (run.state === 'FAILED') this.runError.set(translate(run.error, 'La validación ha fallado.'));
      if (run.state === 'QUEUED' || run.state === 'RUNNING') {
        this.timer = setTimeout(() => void this.refresh(id, token), 800);
      } else {
        void this.loadHistory();
        if (run.state === 'COMPLETED') void this.loadIssues(id, token);
      }
    } catch (error) {
      if (token === this.lookupToken) this.runError.set(this.describe(error));
    }
  }

  private async loadOriginal(id: string, token: number) {
    try {
      const original = await firstValueFrom(this.http.get<Run>(`/api/runs/${encodeURIComponent(id)}`));
      if (token === this.lookupToken) this.original.set(original);
    } catch {
      // The parent link is still available if fetching comparison counts fails.
    }
  }

  private async loadIssues(id: string, token: number) {
    try {
      const issues = await firstValueFrom(this.http.get<Issue[]>(`/api/runs/${encodeURIComponent(id)}/issues`));
      if (token === this.lookupToken) this.issues.set(issues);
    } catch (error) {
      if (token === this.lookupToken) this.issueError.set(this.describe(error));
    }
  }

  private schemaPath(name: string, version: string) {
    return `/api/schemas/${encodeURIComponent(name)}/versions/${encodeURIComponent(version)}`;
  }

  private async perform(busy: WritableSignal<boolean>, message: WritableSignal<Message>, work: () => Promise<string>) {
    if (busy()) return;
    busy.set(true);
    message.set(emptyMessage);
    try {
      message.set({text: await work(), error: false});
    } catch (error) {
      message.set({text: this.describe(error), error: true});
    } finally {
      busy.set(false);
    }
  }

  private describe(error: unknown): string {
    if (!(error instanceof HttpErrorResponse)) return error instanceof Error ? error.message : 'Error inesperado.';
    const fallback: Record<number, string> = {
      0: 'No se pudo conectar con DataClean.',
      400: 'Revisa los datos enviados.',
      404: 'No se encontró el esquema o la ejecución.',
      409: 'La versión ya existe con una definición diferente.',
      413: 'El archivo supera el límite de carga.',
    };
    const detail = typeof error.error?.detail === 'string' ? error.error.detail : null;
    return translate(detail, fallback[error.status] || `Error HTTP ${error.status}.`);
  }
}
