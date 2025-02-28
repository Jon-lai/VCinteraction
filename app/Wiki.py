import wikipedia
def summary(query):
    try:
        return wikipedia.summary(query)
    except:
        return "I couldn't find any information on that."
