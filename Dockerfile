FROM openjdk:11-jdk-bullseye as builder

ARG MAVEN_VERSION=3.9.4
ARG USER_HOME_DIR="/root"
RUN mkdir -p /usr/share/maven
RUN curl -fsSL http://apache.osuosl.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz | tar -xzC /usr/share/maven --strip-components=1
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
ENV MAVEN_HOME /usr/share/maven
ENV MAVEN_CONFIG "$USER_HOME_DIR/.m2"

# speed up Maven JVM a bit
ENV MAVEN_OPTS="-XX:+TieredCompilation -XX:TieredStopAtLevel=1"

RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
# install maven dependency packages (keep in image)
COPY librespot-java /usr/src/app
RUN mvn -T 1C install && rm -rf target

ENTRYPOINT ["tail", "-f", "/dev/null"]
