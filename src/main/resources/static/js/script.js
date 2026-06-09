function sendMessage() {

    let input = document.getElementById("userInput");

    let message = input.value.trim();

    if(message === "") return;

    let chatBox = document.getElementById("chat-box");

    let userDiv = document.createElement("div");

    userDiv.className = "user-message";

    userDiv.innerText = message;

    chatBox.appendChild(userDiv);

    input.value = "";

    chatBox.scrollTop = chatBox.scrollHeight;
}