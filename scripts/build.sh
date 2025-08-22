#!/bin/bash
# Script de build para o projeto IBM MQ Consumer

echo "🚀 Iniciando build do projeto IBM MQ Consumer..."

# Verifica versão do Java
java_version=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | sed '/^1\./s///' | cut -d'.' -f1)
if [ "$java_version" -lt "21" ]; then
    echo "❌ Erro: Java 21 ou superior é necessário. Versão atual: $java_version"
    exit 1
fi

echo "✅ Java $java_version detectado"

# Clean e compile
echo "🧹 Limpando projeto..."
mvn clean

echo "🔨 Compilando..."
mvn compile

# Executa testes
echo "🧪 Executando testes..."
mvn test

if [ $? -eq 0 ]; then
    echo "✅ Build concluído com sucesso!"
    echo "📦 Para executar: mvn spring-boot:run"
else
    echo "❌ Build falhou. Verifique os logs acima."
    exit 1
fi
