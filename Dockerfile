FROM openjdk:8-jdk

ENV AWS_SDK_STS_VERSION=1.11.129
ENV MAVEN_REPO=https://repo1.maven.org/maven2

# Install Node.js and npm
RUN apt-get update \
  && apt-get install -y curl \
  && curl -sL https://deb.nodesource.com/setup_8.x | bash - \
  && apt-get update && apt-get install -y nodejs maven
RUN npm install -g n
RUN n latest

# Install app dependencies
RUN mkdir /src
RUN mkdir /src/logs
RUN touch /src/logs/application.log
WORKDIR /src
COPY . /src
RUN rm -rf /src/node_modules
RUN npm install

# Install AWS multilang daemon dependencies
RUN mkdir /src/repo && cd /src/repo \
    && curl -sLO ${MAVEN_REPO}/com/amazonaws/aws-java-sdk-sts/${AWS_SDK_STS_VERSION}/aws-java-sdk-sts-${AWS_SDK_STS_VERSION}.jar

CMD node_modules/aws-kcl/bin/kcl-bootstrap --java /usr/bin/java -e -c /src/repo -p properties/kcl.properties
