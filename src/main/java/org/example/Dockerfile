FROM openjdk:17
WORKDIR /app

# Копируем весь проект
COPY . .

# Компилируем Java с указанием полного пути
RUN javac -d out src/main/java/org/example/Main.java

# Запускаем программу с указанием classpath
CMD ["java", "-cp", "out", "org.example.Main"]
