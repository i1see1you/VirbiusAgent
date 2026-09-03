{{/*
Expand the name of the chart.
*/}}
{{- define "virbius.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "virbius.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label.
*/}}
{{- define "virbius.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "virbius.labels" -}}
helm.sh/chart: {{ include "virbius.chart" . }}
{{ include "virbius.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "virbius.selectorLabels" -}}
app.kubernetes.io/name: {{ include "virbius.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Component selector labels. Usage: include "virbius.componentLabels" (dict "root" . "component" "control")
*/}}
{{- define "virbius.componentSelectorLabels" -}}
{{ include "virbius.selectorLabels" .root }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
Secret name: existingSecret or {fullname}-secrets.
*/}}
{{- define "virbius.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else }}
{{- printf "%s-secrets" (include "virbius.fullname" .) }}
{{- end }}
{{- end }}

{{/*
Image for a component. Usage: include "virbius.image" (dict "root" . "component" "engine")
*/}}
{{- define "virbius.image" -}}
{{- $registry := .root.Values.global.imageRegistry | default "virbius" }}
{{- printf "%s/virbius-%s:%s" $registry .component .root.Values.global.imageTag }}
{{- end }}

{{/*
imagePullSecrets block.
*/}}
{{- define "virbius.imagePullSecrets" -}}
{{- with .Values.global.imagePullSecrets }}
imagePullSecrets:
{{- toYaml . | nindent 2 }}
{{- end }}
{{- end }}

{{/*
In-cluster Kafka bootstrap, or kafka.bootstrapServers when kafka.enabled=false.
*/}}
{{- define "virbius.kafkaBootstrap" -}}
{{- if .Values.kafka.enabled -}}
{{ include "virbius.fullname" . }}-kafka:9092
{{- else -}}
{{- required "kafka.enabled is false; set kafka.bootstrapServers" .Values.kafka.bootstrapServers -}}
{{- end -}}
{{- end }}

{{/*
In-cluster Redis URL, or redis.url when redis.enabled=false.
*/}}
{{- define "virbius.redisUrl" -}}
{{- if .Values.redis.enabled -}}
redis://{{ include "virbius.fullname" . }}-redis:6379
{{- else -}}
{{- required "redis.enabled is false; set redis.url" .Values.redis.url -}}
{{- end -}}
{{- end }}

{{/*
In-cluster MySQL JDBC URL, or mysql.jdbcUrl when mysql.enabled=false.
*/}}
{{- define "virbius.jdbcUrl" -}}
{{- if .Values.mysql.enabled -}}
jdbc:mariadb://{{ include "virbius.fullname" . }}-mysql:3306/virbius?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&sessionVariables=sql_mode='ALLOW_INVALID_DATES,NO_ENGINE_SUBSTITUTION'
{{- else -}}
{{- required "mysql.enabled is false; set mysql.jdbcUrl" .Values.mysql.jdbcUrl -}}
{{- end -}}
{{- end }}

{{/*
Prompt LLM base URL: in-cluster Ollama, or engine.promptLlm.baseUrl.
*/}}
{{- define "virbius.promptLlmBaseUrl" -}}
{{- if and .Values.ollama.enabled (not .Values.engine.promptLlm.baseUrl) -}}
http://{{ include "virbius.fullname" . }}-ollama:11434
{{- else if .Values.engine.promptLlm.baseUrl -}}
{{ .Values.engine.promptLlm.baseUrl }}
{{- else -}}
{{- fail "ollama.enabled is false; set engine.promptLlm.baseUrl to an external LLM" -}}
{{- end -}}
{{- end }}

{{/*
busybox wait-for TCP. Usage: include "virbius.waitFor" (dict "root" . "name" "mysql" "host" "virbius-mysql" "port" 3306)
*/}}
{{- define "virbius.waitFor" -}}
- name: wait-{{ .name }}
  image: {{ .root.Values.global.waitImage | default "busybox:1.36" | quote }}
  command:
    - sh
    - -c
    - until nc -z {{ .host }} {{ .port }}; do echo waiting for {{ .name }}; sleep 2; done
{{- end }}

{{/*
Wait until Ollama has registered the guard model.
Usage: include "virbius.waitForOllamaModel" (dict "root" . "host" "virbius-ollama" "model" "virbiusguard")
*/}}
{{- define "virbius.waitForOllamaModel" -}}
- name: wait-ollama-model
  image: {{ .root.Values.global.waitImage | default "busybox:1.36" | quote }}
  command:
    - sh
    - -c
    - until wget -q -O - http://{{ .host }}:11434/api/tags | grep -q {{ .model }}; do echo waiting for ollama model {{ .model }}; sleep 5; done
{{- end }}
