#!/usr/bin/env python3
"""Render the self-contained synthetic Kubernetes environment; contains no secrets."""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
NAMESPACE="turaco-synthetic"
items=[{"apiVersion":"v1","kind":"Namespace","metadata":{"name":NAMESPACE,"labels":{"data-classification":"synthetic-only","pod-security.kubernetes.io/enforce":"restricted"}}}]


def secret(key):return {"valueFrom":{"secretKeyRef":{"name":"turaco-local","key":key}}}


def env(values):
    return [{"name":name,**(value if isinstance(value,dict) else {"value":str(value)})} for name,value in values.items()]


def component(name,image,port,settings,health=None,command=None,persistent=None,mounts=None,uid=10001,memory="512Mi"):
    items.append({"apiVersion":"v1","kind":"Service","metadata":{"name":name},"spec":{"selector":{"app":name},"ports":[{"port":port,"targetPort":port}]}})
    container={"name":name,"image":image,"imagePullPolicy":"IfNotPresent","ports":[{"containerPort":port}],"env":env(settings),
        "resources":{"requests":{"cpu":"100m","memory":"64Mi"},"limits":{"cpu":"2","memory":memory}},
        "securityContext":{"allowPrivilegeEscalation":False,"readOnlyRootFilesystem":True,"runAsNonRoot":True,"runAsUser":uid,"capabilities":{"drop":["ALL"]}},
        "volumeMounts":[{"name":"tmp","mountPath":"/tmp"}]}
    volumes=[{"name":"tmp","emptyDir":{}}]
    if command:container["command"]=command
    if health:
        container["readinessProbe"]={"httpGet":{"path":health,"port":port},"initialDelaySeconds":5,"periodSeconds":5}
        container["livenessProbe"]={"httpGet":{"path":health,"port":port},"initialDelaySeconds":30,"periodSeconds":15}
        container["startupProbe"]={"httpGet":{"path":health,"port":port},"periodSeconds":5,"failureThreshold":60}
    if persistent:
        items.append({"apiVersion":"v1","kind":"PersistentVolumeClaim","metadata":{"name":name+"-data"},"spec":{"accessModes":["ReadWriteOnce"],"resources":{"requests":{"storage":"1Gi"}}}})
        volumes.append({"name":"data","persistentVolumeClaim":{"claimName":name+"-data"}})
        container["volumeMounts"].append({"name":"data","mountPath":persistent})
    if mounts:
        for mount in mounts:
            volumes.append(mount[0]);container["volumeMounts"].append(mount[1])
    items.append({"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":name},"spec":{"replicas":1,"strategy":{"type":"Recreate"},"selector":{"matchLabels":{"app":name}},"template":{"metadata":{"labels":{"app":name}},"spec":{"automountServiceAccountToken":False,"securityContext":{"fsGroup":uid,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[container],"volumes":volumes}}}})


realm=(ROOT/"infrastructure/keycloak/turaco-local-realm.json").read_text()
items.append({"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"turaco-local-realm"},"data":{"turaco-local-realm.json":realm}})
component("postgres","postgres:17.11-alpine",5432,{"POSTGRES_DB":"turaco","POSTGRES_USER":"turaco_local","POSTGRES_PASSWORD":secret("DB_PASSWORD"),"PGDATA":"/var/lib/postgresql/data/pgdata"},persistent="/var/lib/postgresql/data",uid=70,mounts=[({"name":"socket","emptyDir":{}},{"name":"socket","mountPath":"/var/run/postgresql"})])
# Match the verified host fixture's heap and reserve room for augmentation/native memory.
component("keycloak","quay.io/keycloak/keycloak:26.4.0",8080,{"KC_BOOTSTRAP_ADMIN_USERNAME":"local-admin","KC_BOOTSTRAP_ADMIN_PASSWORD":secret("KEYCLOAK_ADMIN_PASSWORD"),"KC_HOSTNAME":"http://localhost:8081","JAVA_OPTS_KC_HEAP":"-Xms64m -Xmx384m"},
    command=["/opt/keycloak/bin/kc.sh","start-dev","--import-realm"],persistent="/opt/keycloak/data",uid=1000,memory="1024Mi",health="/realms/turaco-local/.well-known/openid-configuration",
    mounts=[({"name":"realm","configMap":{"name":"turaco-local-realm"}},{"name":"realm","mountPath":"/opt/keycloak/data/import","readOnly":True})])
# Keycloak's first dev-mode augmentation writes its installed libraries. This synthetic
# identity fixture needs a writable root; it is never a production deployment.
items[-1]["spec"]["template"]["spec"]["containers"][0]["securityContext"]["readOnlyRootFilesystem"]=False
component("ai-service","turaco-insurance-ai-service:latest",8000,{"AI_PROVIDER":"mock","AI_SERVICE_TOKEN":secret("AI_SERVICE_TOKEN"),"CONVERSATION_DB":"/data/conversations.sqlite","LANGFUSE_ENABLED":"false"},health="/health",persistent="/data")
component("provider-simulator","turaco-insurance-provider-simulator:latest",9000,{"SIMULATOR_TOKEN":secret("SIMULATOR_TOKEN"),"SIMULATOR_DB":"/data/provider.sqlite"},health="/health",command=[".venv/bin/uvicorn","app.simulator:app","--host","0.0.0.0","--port","9000"],persistent="/data")
settings={"SPRING_PROFILES_ACTIVE":"local","LOCAL_SEED":"true","APP_MODE":"standalone","DB_URL":"jdbc:postgresql://postgres:5432/turaco","DB_USER":"turaco_local","OIDC_ISSUER":"http://localhost:8081/realms/turaco-local","OIDC_JWK_URI":"http://keycloak:8080/realms/turaco-local/protocol/openid-connect/certs","DASHBOARD_ORIGIN":"http://localhost:5173","AI_SERVICE_URL":"http://ai-service:8000","SIMULATOR_URL":"http://provider-simulator:9000","QUEUE_WORKER_ENABLED":"true"}
for key in ["DB_PASSWORD","PAYLOAD_ENCRYPTION_KEY","AI_SERVICE_TOKEN","SIMULATOR_TOKEN","PROVIDER_CALLBACK_TOKEN","WHATSAPP_APP_SECRET","WHATSAPP_VERIFY_TOKEN"]:settings[key]=secret(key)
component("insurance-api","turaco-insurance-insurance-api:latest",8080,settings,health="/actuator/health/readiness",memory="768Mi")
component("claims-dashboard","turaco-insurance-claims-dashboard:latest",8080,{},health="/",uid=101,memory="128Mi")
items.append({"apiVersion":"networking.k8s.io/v1","kind":"NetworkPolicy","metadata":{"name":"namespace-isolation"},"spec":{"podSelector":{},"policyTypes":["Ingress"],"ingress":[{"from":[{"podSelector":{}}]}]}})
for item in items:
    if item["kind"]!="Namespace":item["metadata"]["namespace"]=NAMESPACE
rendered=json.dumps({"apiVersion":"v1","kind":"List","items":items},indent=2)+"\n"
target=ROOT/"infrastructure/kubernetes/resources.json"
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--check",action="store_true");args=parser.parse_args()
    if args.check:
        if not target.exists() or target.read_text()!=rendered:raise SystemExit("Kubernetes bundle is stale; regenerate it")
        print("Kubernetes bundle matches source fixtures and contains no generated secrets")
    else:
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(rendered);print("Rendered synthetic-only Kubernetes bundle")
