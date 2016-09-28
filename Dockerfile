FROM    openjdk:8-jdk

RUN apt-get update
RUN apt-get install -y maven

RUN mkdir /src
WORKDIR /src
COPY . /src

RUN mvn compile

CMD mvn exec:java