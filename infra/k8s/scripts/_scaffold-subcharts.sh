#!/usr/bin/env bash
# Internal scaffolding helper used to write the per-service sub-chart files.
# Run from repo root: bash infra/k8s/scripts/_scaffold-subcharts.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHARTS="$ROOT/charts"

write_helpers() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/_helpers.tpl" <<'TPL'
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
TPL
}

write_serviceaccount() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/serviceaccount.yaml" <<'TPL'
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "svc.serviceAccountName" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
  annotations:
    {{- if .Values.global.workloadIdentity.enabled }}
    azure.workload.identity/client-id: {{ .Values.workloadIdentityClientId | quote }}
    azure.workload.identity/tenant-id: {{ .Values.global.workloadIdentity.tenantId | quote }}
    {{- end }}
    {{- with .Values.serviceAccount.annotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end -}}
TPL
}

write_configmap() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/configmap.yaml" <<'TPL'
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "svc.fullname" . }}-env
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
data:
  LOG_LEVEL: {{ .Values.logLevel | default "info" | quote }}
  OTEL_EXPORTER_OTLP_ENDPOINT: {{ .Values.global.otel.endpoint | quote }}
  OTEL_SERVICE_NAME: {{ .Chart.Name | quote }}
  OTEL_RESOURCE_ATTRIBUTES: {{ printf "service.namespace=%s,service.name=%s" .Values.global.otel.serviceNamespace .Chart.Name | quote }}
  # Kafka / Event Hubs
  KAFKA_BOOTSTRAP_SERVERS: {{ .Values.global.kafka.bootstrapServers | quote }}
  KAFKA_SECURITY_PROTOCOL: {{ .Values.global.kafka.securityProtocol | quote }}
  KAFKA_SASL_MECHANISM: {{ .Values.global.kafka.saslMechanism | quote }}
  KAFKA_SASL_USERNAME: {{ .Values.global.kafka.saslUsername | quote }}
  # Auth / JWT
  JWT_ISSUER: {{ .Values.global.auth.issuer | quote }}
  JWT_AUDIENCE: {{ .Values.global.auth.audience | quote }}
  JWT_ACCESS_TTL_SECONDS: {{ .Values.global.auth.accessTtlSeconds | quote }}
  JWT_REFRESH_TTL_SECONDS: {{ .Values.global.auth.refreshTtlSeconds | quote }}
  JWT_PRIVATE_KEY_PATH: "/etc/msbank/keys/jwt_private.pem"
  JWT_PUBLIC_KEY_PATH: "/etc/msbank/keys/jwt_public.pem"
  # Service URLs
  AUTH_SERVICE_URL: {{ printf "http://%s-auth-service.%s.svc.cluster.local:8081" .Release.Name (include "svc.namespace" .) | quote }}
  ACCOUNTS_SERVICE_URL: {{ printf "http://%s-accounts-service.%s.svc.cluster.local:8082" .Release.Name (include "svc.namespace" .) | quote }}
  TRANSACTIONS_SERVICE_URL: {{ printf "http://%s-transactions-service.%s.svc.cluster.local:8083" .Release.Name (include "svc.namespace" .) | quote }}
  NOTIFICATIONS_SERVICE_URL: {{ printf "http://%s-notifications-service.%s.svc.cluster.local:8084" .Release.Name (include "svc.namespace" .) | quote }}
  # Postgres / Redis (per-service DB URLs are built into env from secret password).
  POSTGRES_HOST: {{ .Values.global.postgres.host | quote }}
  POSTGRES_PORT: {{ .Values.global.postgres.port | quote }}
  POSTGRES_USER: {{ .Values.global.postgres.user | quote }}
  POSTGRES_SSLMODE: {{ .Values.global.postgres.sslMode | quote }}
  REDIS_HOST: {{ .Values.global.redis.host | quote }}
  REDIS_PORT: {{ .Values.global.redis.port | quote }}
  REDIS_TLS: {{ .Values.global.redis.tls | quote }}
  {{- with .Values.extraEnv }}
  {{- range $k, $v := . }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
  {{- end }}
TPL
}

write_service() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/service.yaml" <<'TPL'
apiVersion: v1
kind: Service
metadata:
  name: {{ include "svc.fullname" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type | default "ClusterIP" }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
  selector:
    {{- include "svc.selectorLabels" . | nindent 4 }}
TPL
}

write_deployment() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/deployment.yaml" <<'TPL'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "svc.fullname" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  revisionHistoryLimit: 5
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      {{- include "svc.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "svc.selectorLabels" . | nindent 8 }}
        {{- if .Values.global.workloadIdentity.enabled }}
        azure.workload.identity/use: "true"
        {{- end }}
      annotations:
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      serviceAccountName: {{ include "svc.serviceAccountName" . }}
      {{- with .Values.global.image.pullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.global.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile:
          type: RuntimeDefault
      {{- if and .Values.global.pod (and .Values.global.pod.topologySpread .Values.global.pod.topologySpread.enabled) }}
      topologySpreadConstraints:
        - maxSkew: {{ .Values.global.pod.topologySpread.maxSkew }}
          topologyKey: {{ .Values.global.pod.topologySpread.topologyKey }}
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              {{- include "svc.selectorLabels" . | nindent 14 }}
      {{- end }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "svc.image" . }}
          imagePullPolicy: {{ .Values.image.pullPolicy | default .Values.global.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.targetPort | default .Values.service.port }}
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "svc.fullname" . }}-env
            - secretRef:
                name: {{ include "svc.syncedSecretName" . }}
                optional: true
          env:
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: POD_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            {{- with .Values.env }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          startupProbe:
            httpGet:
              path: {{ .Values.probes.startup.path | default "/healthz" }}
              port: http
            failureThreshold: 30
            periodSeconds: 5
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path | default "/readyz" }}
              port: http
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path | default "/healthz" }}
              port: http
            periodSeconds: 20
            timeoutSeconds: 3
            failureThreshold: 3
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: {{ .Values.readOnlyRootFilesystem | default true }}
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: secrets-store
              mountPath: /mnt/secrets-store
              readOnly: true
            - name: tmp
              mountPath: /tmp
            {{- with .Values.extraVolumeMounts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
      volumes:
        - name: secrets-store
          csi:
            driver: secrets-store.csi.k8s.io
            readOnly: true
            volumeAttributes:
              secretProviderClass: {{ include "svc.spcName" . }}
        - name: tmp
          emptyDir: {}
        {{- with .Values.extraVolumes }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
TPL
}

write_hpa() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/hpa.yaml" <<'TPL'
{{- if .Values.autoscaling.enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "svc.fullname" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "svc.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetMemoryUtilizationPercentage }}
{{- end -}}
TPL
}

write_pdb() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/pdb.yaml" <<'TPL'
{{- if .Values.pdb.enabled -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "svc.fullname" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      {{- include "svc.selectorLabels" . | nindent 6 }}
{{- end -}}
TPL
}

write_networkpolicy() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/networkpolicy.yaml" <<'TPL'
{{- if .Values.networkPolicy.enabled -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "svc.fullname" . }}
  namespace: {{ include "svc.namespace" . }}
  labels:
    {{- include "svc.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "svc.selectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector: {}
        {{- if .Values.networkPolicy.allowIngressFromIngressController }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: ingress-nginx
        {{- end }}
      ports:
        - port: http
          protocol: TCP
  egress:
    - to:
        - podSelector: {}
    - to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
      ports:
        - port: 53
          protocol: UDP
    {{- with .Values.networkPolicy.extraEgress }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end -}}
TPL
}

write_notes() {
  local svc="$1"
  cat > "$CHARTS/$svc/templates/NOTES.txt" <<'TPL'
{{ .Chart.Name }} deployed as part of microservice-bank.

  Service:       {{ include "svc.fullname" . }}.{{ include "svc.namespace" . }}.svc.cluster.local:{{ .Values.service.port }}
  Replicas:      {{ .Values.replicaCount }}
  Image:         {{ include "svc.image" . }}
  Workload ID:   {{ .Values.workloadIdentityClientId }}

Verify rollout:
  kubectl -n {{ include "svc.namespace" . }} rollout status deploy/{{ include "svc.fullname" . }}
TPL
}

for svc in api-gateway auth-service accounts-service transactions-service notifications-service web-portal; do
  write_helpers "$svc"
  write_serviceaccount "$svc"
  write_configmap "$svc"
  write_service "$svc"
  write_deployment "$svc"
  write_hpa "$svc"
  write_pdb "$svc"
  write_networkpolicy "$svc"
  write_notes "$svc"
done

echo "Scaffolded sub-chart templates."
