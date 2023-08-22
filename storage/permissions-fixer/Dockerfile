FROM rust:latest

ENV NEXUS_PATH_PREFIX="/tmp/nexus-fixer,/tmp/scratch"

RUN apt-get update -yqq && apt-get install -yqq --no-install-recommends build-essential

RUN mkdir /opt/src
WORKDIR /opt/src
COPY . /opt/src

CMD ["cargo", "test"]