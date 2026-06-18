package io.hashmatrix.controlplane.provisioning;

/**
 * 开通编排成功产出的接入信息，回写租户目录。
 *
 * @param keycloakOrgId Keycloak Organization id
 * @param namespace     租户 K8s namespace
 * @param dbSchema      租户业务库 schema
 */
public record ProvisioningOutcome(String keycloakOrgId, String namespace, String dbSchema) {}
