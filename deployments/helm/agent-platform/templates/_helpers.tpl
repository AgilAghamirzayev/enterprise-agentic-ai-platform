{{- define "agent.name" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 53 | trimSuffix "-" -}}
{{- end -}}

{{- define "agent.selector" -}}
app.kubernetes.io/name: {{ .root.Chart.Name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "agent.backendEnv" -}}
- name: PLATFORM_DB_URL
  valueFrom:
    secretKeyRef: {name: {{ .Values.database.runtimeSecret | quote }}, key: jdbc-url}
- name: PLATFORM_DB_USER
  valueFrom:
    secretKeyRef: {name: {{ .Values.database.runtimeSecret | quote }}, key: username}
- name: PLATFORM_DB_PASSWORD
  valueFrom:
    secretKeyRef: {name: {{ .Values.database.runtimeSecret | quote }}, key: password}
- name: PLATFORM_TEMPORAL_TARGET
  value: {{ .Values.temporal.target | quote }}
- name: PLATFORM_TEMPORAL_NAMESPACE
  value: {{ .Values.temporal.namespace | quote }}
- name: PLATFORM_TEMPORAL_TLS
  value: {{ .Values.temporal.tls | quote }}
- name: PLATFORM_MODEL
  value: {{ .Values.model.mode | quote }}
{{- if eq .Values.model.mode "catalog" }}
- name: PLATFORM_MODEL_CATALOG
  value: /etc/agent/models/models.json
{{- end }}
- name: OPENAI_MODEL
  value: {{ .Values.model.name | quote }}
- name: PLATFORM_ALLOWED_MODELS
  value: {{ .Values.model.allowed | quote }}
{{- if or .Values.pricing.inputPerMillion .Values.pricing.outputPerMillion }}
- name: PLATFORM_COST_INPUT_USD_PER_MILLION
  value: {{ required "Configure the input token price" .Values.pricing.inputPerMillion | quote }}
- name: PLATFORM_COST_OUTPUT_USD_PER_MILLION
  value: {{ required "Configure the output token price" .Values.pricing.outputPerMillion | quote }}
{{- end }}
- name: PLATFORM_EMBEDDING_MODE
  value: {{ .Values.embeddings.mode | quote }}
{{- if eq .Values.embeddings.mode "learned" }}
- name: PLATFORM_EMBEDDING_PROVIDER
  value: openai
- name: PLATFORM_EMBEDDING_MODEL
  value: {{ .Values.embeddings.name | quote }}
- name: PLATFORM_EMBEDDING_REVISION
  value: {{ required "Set an immutable embedding model revision" .Values.embeddings.revision | quote }}
- name: SPRING_AI_OPENAI_EMBEDDING_BASE_URL
  value: {{ .Values.embeddings.baseUrl | quote }}
- name: SPRING_AI_OPENAI_EMBEDDING_API_KEY
  valueFrom:
    secretKeyRef: {name: {{ .Values.embeddings.secret | quote }}, key: api-key}
{{- end }}
{{- with .Values.metrics.endpoint }}
- name: PLATFORM_METRICS_OTLP_ENABLED
  value: "true"
- name: OTEL_EXPORTER_OTLP_METRICS_ENDPOINT
  value: {{ . | quote }}
{{- end }}
- name: PLATFORM_TRACE_SAMPLE
  value: {{ .Values.tracing.samplingProbability | quote }}
{{- with .Values.tracing.endpoint }}
- name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- end -}}
