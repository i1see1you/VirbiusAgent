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
busybox wait-for TCP. Usage: include "virbius.waitFor" (dict "name" "mysql" "host" "virbius-mysql" "port" 3306)
*/}}
{{- define "virbius.waitFor" -}}
- name: wait-{{ .name }}
  image: busybox:1.36
  command:
    - sh
    - -c
    - until nc -z {{ .host }} {{ .port }}; do echo waiting for {{ .name }}; sleep 2; done
{{- end }}
