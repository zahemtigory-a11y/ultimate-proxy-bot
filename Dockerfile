FROM openjdk:17-jdk-slim
WORKDIR /app

COPY . .

RUN javac -d out src/main/java/org/example/Main.java

CMD ["java", "-cp", "out", "org.example.Main"]
