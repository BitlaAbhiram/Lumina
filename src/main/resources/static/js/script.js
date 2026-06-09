function sendMessage() {

    let input = document.getElementById("userInput");

    let message = input.value.trim();

    if (message === "") return;

    let chatBox = document.getElementById("chat-box");

    let userDiv = document.createElement("div");

    userDiv.className = "user-message";

    userDiv.innerText = message;

    chatBox.appendChild(userDiv);

    input.value = "";

    chatBox.scrollTop = chatBox.scrollHeight;

    setTimeout(() => {

        let botDiv = document.createElement("div");

        botDiv.className = "bot-message";

        botDiv.innerText =
            "Lumina AI is currently thinking...";

        chatBox.appendChild(botDiv);

        chatBox.scrollTop = chatBox.scrollHeight;

    }, 500);
}

document.getElementById("userInput")
    .addEventListener("keypress", function (event) {

        if (event.key === "Enter") {
            sendMessage();
        }
    });