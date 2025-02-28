import requests
from bs4 import BeautifulSoup
from duckduckgo_search import DDGS
import re
import logging

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def clean_text(text: str) -> str:
    """Clean and normalize text content."""
    if not isinstance(text, str):
        return ""
    # Remove extra whitespace and newlines
    text = re.sub(r'\s+', ' ', text)
    # Remove special characters but keep basic punctuation
    # text = re.sub(r'[^\w\s.,!?-]', '', text)
    return text.strip()

def summary(query: str, max_paragraphs: int = 7) -> str:
    """
    Fetch and summarize content from the first DuckDuckGo search result.
    
    Args:
        query: Search query string
        max_paragraphs: Maximum number of paragraphs to include
    
    Returns:
        str: Summarized content or error message
    """
    try:
        if not query or not isinstance(query, str):
            return "Please provide a valid search query."
        
        with DDGS() as ddgs:
            results = list(ddgs.text(query, max_results=2))
            if results:
                url = results[0].get('href')
                if url:
                    response = requests.get(
                        url,
                        timeout=5,
                        headers={'User-Agent': 'Mozilla/5.0'}
                    )
                    response.raise_for_status()
                    soup = BeautifulSoup(response.text, "html.parser")
        
                    # Extract text from paragraphs
                    paragraphs = [p for p in soup.find_all("p") if p.get_text().strip()]
                    if paragraphs:
                        summary = " ".join(
                            clean_text(p.get_text())
                            for p in paragraphs[:max_paragraphs]
                        )
                        return summary if summary else "No relevant content found."
                    else:
                        return "Could not find any search results."
                else:
                    return "Could not find any search results."
            else:
                return "Could not find any search results."
    
    except requests.RequestException as e:
        logger.error(f"Error fetching URL: {str(e)}")
        return f"Error fetching URL: {str(e)}"
    except Exception as e:
        logger.error(f"Search error: {str(e)}")
        return f"An error occurred while searching: {str(e)}"