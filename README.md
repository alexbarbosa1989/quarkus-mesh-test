# quarkus-mesh-test

Minimal Quarkus app for testing sidecar injection / mTLS behavior against
Red Hat OpenShift Service Mesh 2.6.

## Important: OCP cluster has already installed OSSM 2.6

## Endpoints

- `GET /hello` - plain text sanity check
- `GET /info` - JSON with pod hostname + timestamp, useful for confirming
  which pod handled a request and eyeballing whether traffic went through
  the Envoy sidecar
- `GET /q/health/live` and `/q/health/ready` - probes

## Build S2I strategy, Git source (uses `oc new-app`, matches the `image~git-url` pattern)

This is the source workflow — OpenShift pulls straight from your Git repo and
sets up an image-change trigger, so future pushes can auto-rebuild/redeploy.
It requires the `.s2i/environment` file already included in this project,
which tells the Java S2I builder how to handle Quarkus's fast-jar layout —
without it, the builder's default assemble script looks for a single jar
under `target/` and won't find a runnable entry point, since fast-jar splits
the app across `lib/`, `app/`, `quarkus/`, and `quarkus-run.jar`.

```bash
# push this project to your own GitHub repo first, then:
oc new-project quarkus-mesh-test
oc new-app registry.redhat.io/ubi8/openjdk-17:latest~https://github.com/alexbarbosa1989/quarkus-mesh-test.git --name=quarkus-mesh-test
oc logs -f bc/quarkus-mesh-test
```

## Deploy

Manifests are in `k8s/`, applied in order:

```bash
oc apply -f k8s/03-app.yaml   # namespace + deployment + service + route
oc apply -f k8s/01-smcp.yaml  # ServiceMeshControlPlane (istio-system)
oc apply -f k8s/02-smm.yaml   # enrolls quarkus-mesh-test into the mesh
```

Before applying `01-smcp.yaml`, confirm your operator subscription actually
resolved to a 2.6.x CSV:

```bash
oc get csv -n openshift-operators | grep servicemesh
```

If this cluster/namespace ever ran OSSM 3 (Sail Operator) previously, check
for leftover CRs before enrolling:

```bash
oc get istio,istiocni -A
```

## Verifying the sidecar actually injected

```bash
oc get pods -n quarkus-mesh-test
# READY should show 2/2 (app container + istio-proxy)

oc get pod -n quarkus-mesh-test -l app=quarkus-mesh-test -o jsonpath='{.items[0].spec.containers[*].name}'
```

