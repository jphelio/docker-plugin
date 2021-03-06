package com.nirima.jenkins.plugins.docker.client;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.DockerCmdExecFactory;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.jaxrs.DockerCmdExecFactoryImpl;

import java.util.ServiceLoader;

/**
 * Created by magnayn on 10/07/2015.
 */
public class ClientBuilderForPlugin  {

    private final DockerClientConfig config;

    private ClientBuilderForPlugin(DockerClientConfig config) {
        this.config = config;
    }

    public static ClientBuilderForPlugin getInstance(DockerClientConfig.DockerClientConfigBuilder dockerClientConfigBuilder) {
        return getInstance(dockerClientConfigBuilder.build());
    }

    public static ClientBuilderForPlugin getInstance(DockerClientConfig dockerClientConfig) {
        return new ClientBuilderForPlugin(dockerClientConfig);
    }

    public static ClientBuilderForPlugin getInstance(ClientConfigBuilderForPlugin dockerClientConfig) {
        return new ClientBuilderForPlugin(dockerClientConfig.build());
    }


    public DockerClient build() {
        return DockerClientBuilder.getInstance(config)
                .withDockerCmdExecFactory(new DockerCmdExecFactoryImpl())
                .build();

    }
}
