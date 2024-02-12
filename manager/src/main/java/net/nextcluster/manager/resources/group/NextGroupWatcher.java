/*
 * MIT License
 *
 * Copyright (c) 2024 nextCluster
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.nextcluster.manager.resources.group;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import net.nextcluster.driver.NextCluster;
import net.nextcluster.driver.resource.group.NextGroup;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

public class NextGroupWatcher implements ResourceEventHandler<NextGroup> {

    @Override
    public void onAdd(NextGroup group) {
        deploy(group);
    }

    @Override
    public void onUpdate(NextGroup oldObj, NextGroup newObj) {
        deploy(newObj);
    }

    @Override
    public void onDelete(NextGroup obj, boolean deletedFinalStateUnknown) {
        NextCluster.instance().kubernetes().apps().deployments().withName(obj.getMetadata().getName()).delete();
    }

    private void deploy(NextGroup group) {
        final var client = NextCluster.instance().kubernetes();
        final Collection<ContainerPort> ports = Stream.of(group.getSpec().getBase().getPorts())
            .map(port -> new ContainerPortBuilder()
                .withContainerPort(port.getPort())
                .withProtocol(port.getProtocol())
                .build())
            .toList();

        // @formatter:off
        final var deployment = new DeploymentBuilder()
            .withNewMetadata()
                .withName(group.getMetadata().getName())
                .withNamespace(group.getMetadata().getNamespace())
                .addToLabels("nextcluster", "true")
            .endMetadata()
            .withNewSpec()
                .withReplicas(group.minOnline())
                .withSelector(
                    new LabelSelectorBuilder()
                        .addToMatchLabels("nextcluster/group", group.name())
                        .build()
                )
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels("nextcluster", "true")
                        .addToLabels("nextcluster/group", group.name())
                        .addToLabels("nextcluster/fallback", String.valueOf(group.isFallback()))
                        .addToLabels("nextcluster/type", group.platform().type())
                    .endMetadata()
                    .withNewSpec()
                        .withServiceAccountName("nextcluster")
                        .addNewContainer()
                            .withName(group.name())
                            .withImage(group.image())
                            .withImagePullPolicy("Always")
                            .addAllToPorts(ports)
                            .addAllToEnv(
                                group.environment().entrySet().stream().map(env -> new EnvVarBuilder()
                                    .withName(env.getKey())
                                    .withValue(env.getValue())
                                    .build())
                                .toList()
                            )
                            /*.withNewResources()
                                .addToLimits("memory", new Quantity(group.maxMemory() + "Mi"))
                            .endResources()*/
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
        // @formatter:on
        client.apps().deployments().resource(deployment).serverSideApply();

        Arrays.stream(group.getSpec().getBase().getPorts())
            .filter(port -> port.getExpose() != null)
            .forEach(port -> {
                // @formatter:off
                final var name = port.getName() != null ? port.getName() : group.name();
                final var service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(name)
                    .endMetadata()
                    .withNewSpec()
                        .addToSelector("nextcluster/group", group.name())
                        .withType("NodePort")
                        .addNewPort()
                            .withPort(port.getPort())
                            .withTargetPort(new IntOrString(port.getPort()))
                            .withProtocol(port.getProtocol())
                            .withNodePort(port.getExpose())
                        .endPort()
                    .endSpec()
                    .build();
                // @formatter:on
                client.services().resource(service).serverSideApply();
            });
    }
}
