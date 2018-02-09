package io.vamp.container_driver.kubernetes

import com.google.gson.reflect.TypeToken
import io.kubernetes.client.models._
import io.vamp.common.akka.CommonActorLogging
import io.vamp.container_driver.ContainerDriverActor.DeploymentServices
import io.vamp.container_driver.{ ContainerDriver, _ }
import io.vamp.model.artifact._
import io.vamp.model.reader.{ MegaByte, Quantity }

import scala.collection.JavaConverters._

object KubernetesDeployment {
  val dialect = "kubernetes"
}

trait KubernetesDeployment extends KubernetesArtifact {
  this: KubernetesContainerDriver with CommonActorLogging ⇒

  private val deploymentServiceIdLabel = "deployment-service"

  private val workflowIdLabel = "workflow"

  override protected def supportedDeployableTypes: List[DeployableType] = RktDeployableType :: DockerDeployableType :: Nil

  protected def schema: Enumeration

  protected def labels(id: String, value: String) = Map(ContainerDriver.labelNamespace() → value, ContainerDriver.withNamespace(value) → id)

  protected def containerServices(deploymentServices: List[DeploymentServices], equalityRequest: ServiceEqualityRequest): List[ContainerService] = {
    deploymentServices.flatMap { deploymentService ⇒
      deploymentService.services.map { service ⇒
        val id = appId(deploymentService.deployment, service.breed)
        log.debug(s"kubernetes get $id")
        k8sClient.cache.readRequestWithCache(
          K8sCache.deployment,
          id,
          () ⇒ k8sClient.extensionsV1beta1Api.readNamespacedDeploymentStatus(id, namespace.name, null)
        ).flatMap { deployment ⇒
            containers(
              id,
              deploymentServiceIdLabel,
              deployment.getSpec.getTemplate.getSpec.getContainers.asScala,
              deployment.getSpec.getReplicas
            ) map { containerService ⇒
              val k8sContainer = deployment.getSpec.getTemplate.getSpec.getContainers.asScala.map(c ⇒ c.getName → c).toMap.get(id)
              val cluster = deploymentService.deployment.clusters.find { c ⇒ c.services.exists { s ⇒ s.breed.name == service.breed.name } }
              val processClusterEquality = k8sContainer.isDefined && cluster.isDefined

              val equality = ServiceEqualityResponse(
                deployable = !equalityRequest.deployable || (k8sContainer.isDefined && checkDeployable(service, k8sContainer.get)),
                ports = !equalityRequest.ports || (processClusterEquality && checkPorts(deploymentService.deployment, cluster.get, service, k8sContainer.get)),
                environmentVariables = !equalityRequest.environmentVariables || (processClusterEquality && checkEnvironmentVariables(deploymentService.deployment, cluster.get, service, k8sContainer.get))
              )

              ContainerService(deploymentService.deployment, service, Option(containerService), None, equality)
            }
          } getOrElse ContainerService(deploymentService.deployment, service, None)
      }
    }
  }

  protected def containerWorkflow(workflow: Workflow): ContainerWorkflow = {
    val id = appId(workflow)
    log.debug(s"kubernetes get $id")
    k8sClient.cache.readRequestWithCache(
      K8sCache.deployment,
      id,
      () ⇒ k8sClient.extensionsV1beta1Api.readNamespacedDeploymentStatus(id, namespace.name, null)
    ).map { deployment ⇒
        ContainerWorkflow(
          workflow,
          containers(
            id,
            workflowIdLabel,
            deployment.getSpec.getTemplate.getSpec.getContainers.asScala,
            deployment.getSpec.getReplicas
          )
        )
      } getOrElse ContainerWorkflow(workflow, None)
  }

  private def containers(id: String, selector: String, v1Containers: Seq[V1Container], replicas: Int): Option[Containers] = {
    val ports = v1Containers.headOption.map(_.getPorts.asScala.map(_.getContainerPort.toInt)).getOrElse(Nil).toList
    val scale: Option[DefaultScale] = v1Containers.headOption.flatMap { item ⇒
      val request = item.getResources.getRequests.asScala
      for {
        cpu ← request.get("cpu")
        memory ← request.get("memory")
      } yield DefaultScale(Quantity.of(cpu), MegaByte.of(memory), replicas)
    }
    if (scale.isDefined) {
      val instances = pods(id, selector).map { pod ⇒
        ContainerInstance(pod.getMetadata.getName, pod.getStatus.getPodIP, ports, pod.getStatus.getPhase.contains("Running"))
      }.toList
      Option(Containers(scale.get, instances))
    }
    else None
  }

  private def checkDeployable(service: DeploymentService, container: V1Container): Boolean = service.breed.deployable.definition == container.getImage

  private def checkPorts(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, container: V1Container): Boolean = {
    container.getPorts.asScala.map(_.getContainerPort.toInt).toSet == portMappings(deployment, cluster, service, "").map(_.containerPort).toSet
  }

  private def checkEnvironmentVariables(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, container: V1Container): Boolean = {
    container.getEnv.asScala.map(e ⇒ e.getName → e.getValue).toMap == environment(deployment, cluster, service)
  }

  protected def deploy(deployment: Deployment, cluster: DeploymentCluster, service: DeploymentService, update: Boolean): Unit = {
    validateDeployable(service.breed.deployable)

    val id = appId(deployment, service.breed)
    if (update) log.info(s"kubernetes update app: $id") else log.info(s"kubernetes create app: $id")

    val (local, dialect) = (deployment.dialects.get(KubernetesDeployment.dialect), cluster.dialects.get(KubernetesDeployment.dialect), service.dialects.get(KubernetesDeployment.dialect)) match {
      case (_, _, Some(d))       ⇒ Some(service) → d
      case (_, Some(d), None)    ⇒ None → d
      case (Some(d), None, None) ⇒ None → d
      case _                     ⇒ None → Map()
    }

    deploy(
      id = id,
      docker = docker(deployment, cluster, service),
      scale = service.scale.get,
      environmentVariables = environment(deployment, cluster, service),
      labels = labels(deployment, cluster, service) ++ labels(id, deploymentServiceIdLabel),
      update = update,
      dialect = interpolate(deployment, local, dialect.asInstanceOf[Map[String, Any]])
    )
  }

  protected def undeploy(deployment: Deployment, service: DeploymentService): Unit = {
    val id = appId(deployment, service.breed)
    log.info(s"kubernetes delete app: $id")
    undeploy(id, deploymentServiceIdLabel)
  }

  protected def deploy(workflow: Workflow, update: Boolean): Unit = {

    validateDeployable(workflow.breed.asInstanceOf[DefaultBreed].deployable)

    val id = appId(workflow)
    if (update) log.info(s"kubernetes update workflow: ${workflow.name}") else log.info(s"kubernetes create workflow: ${workflow.name}")

    val scale = workflow.scale.get.asInstanceOf[DefaultScale]
    val dialect = workflow.dialects.getOrElse(KubernetesDeployment.dialect, Map())

    deploy(
      id = id,
      docker = docker(workflow),
      scale = scale,
      environmentVariables = environment(workflow),
      labels = labels(workflow) ++ labels(id, workflowIdLabel),
      update = update,
      dialect = dialect.asInstanceOf[Map[String, Any]]
    )
  }

  protected def undeploy(workflow: Workflow): Unit = {
    val id = appId(workflow)
    log.info(s"kubernetes delete workflow: ${workflow.name}")
    undeploy(id, workflowIdLabel)
  }

  private def deploy(id: String, docker: Docker, scale: DefaultScale, environmentVariables: Map[String, String], labels: Map[String, String], update: Boolean, dialect: Map[String, Any]): Unit = {
    val app = KubernetesDeploymentRequest(
      name = id,
      docker = docker,
      replicas = scale.instances,
      cpu = scale.cpu.value,
      mem = Math.round(scale.memory.value).toInt,
      privileged = docker.privileged,
      env = environmentVariables,
      cmd = Nil,
      args = Nil,
      labels = labels,
      dialect = dialect
    )

    if (update)
      k8sClient.cache.writeRequestWithCache(
        K8sCache.deployment,
        id,
        () ⇒ k8sClient.extensionsV1beta1Api.replaceNamespacedDeployment(
          id,
          namespace.name,
          k8sClient.extensionsV1beta1Api.getApiClient.getJSON.deserialize(app.toString, new TypeToken[ExtensionsV1beta1Deployment]() {}.getType),
          null
        )
      )
    else k8sClient.cache.writeRequestWithCache(
      K8sCache.deployment,
      id,
      () ⇒ k8sClient.extensionsV1beta1Api.createNamespacedDeployment(
        namespace.name,
        k8sClient.extensionsV1beta1Api.getApiClient.getJSON.deserialize(app.toString, new TypeToken[ExtensionsV1beta1Deployment]() {}.getType),
        null
      )
    )
  }

  private def undeploy(id: String, selector: String): Unit = {
    k8sClient.cache.writeRequestWithCache(
      K8sCache.deployment,
      id,
      () ⇒ k8sClient.extensionsV1beta1Api.deleteNamespacedDeployment(id, namespace.name, new V1DeleteOptions, null, null, null, null)
    )
    replicas(id, selector).foreach { rs ⇒
      k8sClient.cache.writeRequestWithCache(
        K8sCache.replicaSet,
        rs.getMetadata.getName,
        () ⇒ k8sClient.extensionsV1beta1Api.deleteNamespacedReplicaSet(rs.getMetadata.getName, namespace.name, new V1DeleteOptions, null, null, null, null)
      )
    }
    pods(id, selector).foreach { pod ⇒
      k8sClient.cache.writeRequestWithCache(
        K8sCache.pod,
        pod.getMetadata.getName,
        () ⇒ k8sClient.coreV1Api.deleteNamespacedPod(pod.getMetadata.getName, namespace.name, new V1DeleteOptions, null, null, null, null)
      )
    }
  }

  private def pods(id: String, value: String): Seq[V1Pod] = {
    k8sClient.cache.readRequestWithCache(
      K8sCache.pod,
      () ⇒ k8sClient.coreV1Api.listNamespacedPod(namespace.name, null, null, labelSelector(labels(id, value)), null, null, null).getItems.asScala
    )
  }

  private def replicas(id: String, value: String): Seq[V1beta1ReplicaSet] = {
    k8sClient.cache.readRequestWithCache(
      K8sCache.replicaSet,
      () ⇒ k8sClient.extensionsV1beta1Api.listNamespacedReplicaSet(namespace.name, null, null, labelSelector(labels(id, value)), null, null, null).getItems.asScala
    )
  }
}
