{{/* vim: set filetype=mustache: */}}

{{- define "svc.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "svc.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "svc.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "svc.labels" -}}
helm.sh/chart: {{ include "svc.chart" . }}
{{ include "svc.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: microservice-bank
app.kubernetes.io/component: {{ .Chart.Name }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end -}}

{{- define "svc.selectorLabels" -}}
app.kubernetes.io/name: {{ include "svc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "svc.namespace" -}}
{{- default .Release.Namespace .Values.global.namespace -}}
{{- end -}}

{{- define "svc.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "svc.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "svc.image" -}}
{{- $registry := .Values.image.registry | default .Values.global.image.registry -}}
{{- $repoPrefix := .Values.global.image.repositoryPrefix | default "" -}}
{{- $repo := .Values.image.repository | default (printf "%s/%s" $repoPrefix .Chart.Name) -}}
{{- $tag := .Values.image.tag | default .Values.global.image.tag | default .Chart.AppVersion -}}
{{- printf "%s/%s:%s" $registry $repo $tag -}}
{{- end -}}

{{/*
SecretProviderClass name created by the umbrella chart.
Pattern: <release>-microservice-bank-kv
*/}}
{{- define "svc.spcName" -}}
{{- printf "%s-microservice-bank-kv" .Release.Name -}}
{{- end -}}

{{- define "svc.syncedSecretName" -}}
{{- printf "%s-microservice-bank-kv-synced" .Release.Name -}}
{{- end -}}
